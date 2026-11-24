(ns taia.ds.datastar.adapters.handlers.connected-component-handler
  "Handler for establishing persistent SSE connections to component instances.
   Manages a `!connections` atom that tracks, per (component-id, user-id), the
   open SSE stream AND the component flow (`taia.ds.datastar.use-cases.process-event`)
   that lives as long as it.

   On connection open, the flow is started (holding the ctx between events),
   the INIT event runs through it, and its render is sent down the stream.
   Afterwards:
   - a POST on the same path (`handler-connected-component-event`) runs through
     the SAME flow and answers with the render, like a default component;
   - `send-event!` puts a server event on the flow, and its render is pushed
     down the open stream."
  (:require
   [taia.ds.logging.interface :as log]
   [taia.ds.datastar.use-cases.process-event :as process-event]
   [taia.ds.datastar.use-cases.process-event.common :as common]
   [taia.ds.datastar.adapters.handlers.request :as request]
   [taia.ds.datastar.adapters.handlers.default-component-handler :as default-handler]
   [taia.ds.datastar.adapters.handlers.discovery :as discovery]
   [taia.ds.datastar.adapters.handlers.observer :as observer]
   [taia.ds.datastar.adapters.handlers.response :refer [send-effect! render-response error-response]]
   [starfederation.datastar.clojure.adapter.ring :refer [->sse-response on-open on-close]]
   [starfederation.datastar.clojure.api :as d*]
   [dev.onionpancakes.chassis.compiler :as hc]
   [dev.onionpancakes.chassis.core :as h]
   [clojure.core.async :refer [chan close! <!! put! offer!]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

;; {component-id {user-id {:sse-gen sse-gen :flow component-flow}}}
;; :flow is nil for a stream registered without one (tests, legacy callers).
(def !connections (atom {}))

(defn- sse-gen-of
  "The sse-gen registered under (component-id, user-id), or nil."
  [component-id user-id]
  (get-in @!connections [component-id user-id :sse-gen]))

(defn- stop-flow-of!
  "Stops the flow of a registry entry, if it has one."
  [{:keys [flow]}]
  (when flow
    (process-event/stop-flow! flow)))

(tests
 "sse-gen-of: the sse-gen registered under (component-id, user-id)"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse-1 :flow nil}}})
 (sse-gen-of "cid-1" "u1") := ::sse-1

 "sse-gen-of: nil when nothing is registered there"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse-1 :flow nil}}})
 (sse-gen-of "cid-1" "u2") := nil
 (reset! !connections {})

 "stop-flow-of!: an entry with a flow → its flow is stopped"
 (with-redefs [process-event/stop-flow! (spy/spy (constantly ::stopped))]
   (stop-flow-of! {:sse-gen ::sse-1 :flow ::flow}) := ::stopped
   (spy/called-once-with? process-event/stop-flow! ::flow) := true)

 "stop-flow-of!: an entry without a flow → nothing to stop"
 (with-redefs [process-event/stop-flow! (spy/spy)]
   (stop-flow-of! {:sse-gen ::sse-1 :flow nil}) := nil
   (spy/not-called? process-event/stop-flow!) := true))

;; Channel for pushing UI updates after the initial SSE response.
;; Message format: [component-id user-id [[:patch/:broadcast [:div ...] opts] ...]]
(def !ui-chan (chan 100))

;;; Per-user write channels
;; Each user gets a dedicated channel + virtual thread for SSE writes.
;; This ensures: (1) writes to different users never block each other,
;; (2) writes to the same user are ordered, (3) slow clients only stall themselves.
;; {user-id {:chan channel :thread Thread}}
(def !user-chans (atom {}))

(defn- start-user-consumer!
  "Starts a virtual thread that drains a per-user write channel.
   Each message is a (fn [] ...) thunk that performs blocking SSE I/O."
  [user-id ch]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (when-let [write-fn (<!! ch)]
         (try
           (write-fn)
           (catch Throwable e
             (log/trace ::user-write-error
                        [:user-id user-id
                         :error (.getMessage e)]
                        nil)))
         (recur))))))

(tests
 "start-user-consumer!: runs the channel's write thunks in order, keeps going
  after one throws, and ends when the channel closes"
 (def ^:private consumer-ch (chan 10))
 (def ^:private consumed (spy/spy))
 (put! consumer-ch #(consumed 1))
 (put! consumer-ch #(throw (ex-info "Broken pipe" {})))
 (put! consumer-ch #(consumed 2))
 (close! consumer-ch)
 (def ^:private consumer (start-user-consumer! "u1" consumer-ch))
 (.join ^Thread consumer 1000)
 (.isAlive ^Thread consumer) := false
 (spy/calls consumed) := [[1] [2]])

(defn- ensure-user-chan!
  "Returns the channel for a user-id, creating one (with its consumer thread) if needed."
  [user-id]
  (or (get-in @!user-chans [user-id :chan])
      (let [ch (chan 100)
            updated (swap! !user-chans
                           (fn [m]
                             (if (contains? m user-id)
                               m
                               (assoc m user-id {:chan ch}))))
            winner (get-in updated [user-id :chan])]
        (if (identical? winner ch)
          (let [t (start-user-consumer! user-id ch)]
            (swap! !user-chans assoc-in [user-id :thread] t)
            ch)
          (do (close! ch)
              winner)))))

(defn- destroy-user-chan!
  "Closes and removes the channel for a user-id."
  [user-id]
  (when-let [{:keys [chan]} (get @!user-chans user-id)]
    (close! chan)
    (swap! !user-chans dissoc user-id)))

(defn- user-has-connections?
  "Returns true if the user has at least one SSE connection across any component-id."
  [user-id connections-map]
  (some (fn [[_cid user-map]] (contains? user-map user-id))
        connections-map))

(tests
 "ensure-user-chan!: the first call creates the user's channel and starts its
  consumer on it"
 (reset! !user-chans {})
 (with-redefs [start-user-consumer! (spy/spy (constantly ::consumer-thread))]
   (def ^:private created-ch (ensure-user-chan! "user-a"))
   @!user-chans := {"user-a" {:chan created-ch :thread ::consumer-thread}}
   (spy/called-once-with? start-user-consumer! "user-a" created-ch) := true)
 (reset! !user-chans {})

 "ensure-user-chan! is idempotent — the user's existing channel, no second consumer"
 (reset! !user-chans {"user-a" {:chan ::existing-ch :thread ::consumer-thread}})
 (with-redefs [start-user-consumer! (spy/spy)]
   (ensure-user-chan! "user-a") := ::existing-ch
   (spy/not-called? start-user-consumer!) := true)
 (reset! !user-chans {})

 "ensure-user-chan! creates different channels for different users"
 (reset! !user-chans {})
 (with-redefs [start-user-consumer! (spy/spy)]
   (identical? (ensure-user-chan! "user-a") (ensure-user-chan! "user-b")) := false)
 (reset! !user-chans {})

 "destroy-user-chan!: closes the user's channel and forgets it"
 (def ^:private doomed-ch (chan 1))
 (reset! !user-chans {"user-a" {:chan doomed-ch} "user-b" {:chan ::other-ch}})
 (destroy-user-chan! "user-a")
 @!user-chans := {"user-b" {:chan ::other-ch}}
 (<!! doomed-ch) := nil

 "destroy-user-chan!: no channel for the user → a no-op"
 (reset! !user-chans {"user-b" {:chan ::other-ch}})
 (destroy-user-chan! "user-a") := nil
 @!user-chans := {"user-b" {:chan ::other-ch}}
 (reset! !user-chans {})

 "user-has-connections?: truthy when the user has a stream under any component-id"
 (user-has-connections? "user-a" {"cid-1" {"user-b" {}} "cid-2" {"user-a" {}}}) := true

 "user-has-connections?: nil when the user has none"
 (user-has-connections? "user-a" {"cid-1" {"user-b" {}} "cid-2" {}}) := nil)

(defn- remove-connection-if-current!
  "Removes the (component-id, user-id) registry entry only if it still points
   at sse-gen, and stops that entry's flow. A client reconnect re-registers
   under the same key, so a late close/write-failure of the OLD stream must
   not evict (or stop the flow of) the NEW live one. Whoever removes the entry
   stops its flow — a stale-write drop as much as an on-close — so a flow
   never outlives its registry entry.
   Returns true when the entry was removed."
  [component-id user-id sse-gen]
  (let [[old _new] (swap-vals! !connections
                               (fn [m]
                                 (if (identical? (get-in m [component-id user-id :sse-gen]) sse-gen)
                                   (update m component-id dissoc user-id)
                                   m)))
        entry      (get-in old [component-id user-id])
        removed?   (identical? (:sse-gen entry) sse-gen)]
    (when removed?
      (stop-flow-of! entry))
    removed?))

(tests
 "remove-connection-if-current!: the entry still points at sse-gen → it is
  removed and its flow stopped"
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-new :flow ::flow-new}
                                "user-b" {:sse-gen ::sse-b :flow nil}}})
 (with-redefs [stop-flow-of! (spy/spy)]
   (remove-connection-if-current! "cid-1" "user-a" ::sse-new) := true
   @!connections := {"cid-1" {"user-b" {:sse-gen ::sse-b :flow nil}}}
   (spy/called-once-with? stop-flow-of! {:sse-gen ::sse-new :flow ::flow-new}) := true)

 "reconnect race: a late close (or failed write) of the OLD stream must not
  evict the NEW one, nor stop its flow. Safari churns SSE connections (tab
  suspension, HTTP/3); the client reconnects under the same (component-id,
  user-id) key BEFORE Jetty notices the old socket died and fires its on-close."
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-new :flow ::flow-new}
                                "user-b" {:sse-gen ::sse-b :flow nil}}})
 (with-redefs [stop-flow-of! (spy/spy)]
   (remove-connection-if-current! "cid-1" "user-a" ::sse-old) := false
   @!connections := {"cid-1" {"user-a" {:sse-gen ::sse-new :flow ::flow-new}
                              "user-b" {:sse-gen ::sse-b :flow nil}}}
   (spy/not-called? stop-flow-of!) := true)
 (reset! !connections {}))

(def slow-write-threshold-ms
  "A context-less write (heartbeat) slower than this, or one that waited longer
   than this in the queue, is reported as its own `::sse-write-slow` span so it
   reaches Tempo without tracing every 15 s heartbeat."
  1000)

(defn- report-slow-write!
  "Emits the `::sse-write-slow` span (a defn, not inline, so tests can observe it)."
  [component-id user-id queue-wait-ms write-ms]
  (log/trace ::sse-write-slow
             [:component-id component-id
              :user-id user-id
              :queue-wait-ms queue-wait-ms
              :write-ms write-ms]
             nil))

(defn- traced-write
  "Wraps a write thunk so the socket write is visible in Tempo.

   Why: SSE writes for one user are serialized on one virtual thread, so a
   write blocked on a half-dead stream (Safari over HTTP/3 on prod) delays
   every later write for that user — and nothing measured it. The span carries
   both the write's own duration and how long the thunk sat in the queue.

   Writes enqueued from inside a trace (agent turn pushes) nest under that
   trace via the captured mulog context; context-less writes (heartbeats) only
   emit a span when they are slow, so Tempo is not flooded."
  [component-id user-id write-fn]
  (let [ctx         (log/local-context)
        enqueued-at (System/currentTimeMillis)]
    (fn []
      (let [queue-wait-ms (- (System/currentTimeMillis) enqueued-at)]
        (if (:mulog/root-trace ctx)
          (log/with-context ctx
            (log/trace ::sse-write
                       [:component-id component-id
                        :user-id user-id
                        :queue-wait-ms queue-wait-ms]
                       (write-fn)))
          (let [started (System/currentTimeMillis)]
            (try
              (write-fn)
              (finally
                (let [elapsed (- (System/currentTimeMillis) started)]
                  (when (or (> elapsed slow-write-threshold-ms)
                            (> queue-wait-ms slow-write-threshold-ms))
                    (report-slow-write! component-id user-id queue-wait-ms elapsed)))))))))))

(tests
 "traced-write: a write enqueued INSIDE a trace runs under that trace (the
  captured mulog context is re-established on the consumer thread); the
  thunk's result is preserved"
 (with-redefs [log/local-context (constantly {:mulog/root-trace :root-1 :turn "t1"})]
   ;; the write answers with the :turn of the context it ran under
   (def ^:private in-trace-write (spy/spy (fn [] (:turn (com.brunobonacci.mulog/local-context)))))
   ((traced-write "cid-1" "u1" in-trace-write)) := "t1"
   (spy/called-once? in-trace-write) := true)

 "traced-write: a context-less FAST write reports nothing"
 (with-redefs [log/local-context  (constantly {})
               report-slow-write! (spy/spy)]
   ((traced-write "cid-1" "u1" (constantly :fast))) := :fast
   (spy/not-called? report-slow-write!) := true)

 "traced-write: a context-less SLOW write reports ::sse-write-slow with the
  elapsed write time"
 (with-redefs [log/local-context       (constantly {})
               report-slow-write!      (spy/spy)
               slow-write-threshold-ms 0]
   ((traced-write "cid-1" "u1" (fn [] (Thread/sleep 5) :slow))) := :slow
   ;; [cid uid queue-wait-ms write-ms]
   (spy/calls report-slow-write!) := [["cid-1" "u1" _ _]]
   (-> report-slow-write!
       spy/last-call
       (nth 3)
       (>= 5))
   := true))

(defn- enqueue-write!
  "Enqueues a write thunk onto a user's channel. If the buffer is full,
   the write is dropped and sse-gen's connection is considered stale."
  [component-id user-id sse-gen write-fn]
  (when-let [ch (get-in @!user-chans [user-id :chan])]
    (when-not (offer! ch (traced-write component-id user-id write-fn))
      (log/trace ::user-write-queue-full
                 [:component-id component-id :user-id user-id]
                 nil)
      (remove-connection-if-current! component-id user-id sse-gen))))

(tests
 "enqueue-write!: the write, traced, goes onto the user's channel"
 (def ^:private write-thunk (fn [] :written))
 (def ^:private roomy-ch (chan 1))
 (reset! !user-chans {"u1" {:chan roomy-ch}})
 (with-redefs [traced-write                  (spy/spy (constantly ::traced-write))
               remove-connection-if-current! (spy/spy)]
   (enqueue-write! "cid-1" "u1" ::sse-1 write-thunk) := nil
   (spy/called-once-with? traced-write "cid-1" "u1" write-thunk) := true
   (spy/not-called? remove-connection-if-current!) := true)
 (clojure.core.async/poll! roomy-ch) := ::traced-write
 (reset! !user-chans {})

 "enqueue-write!: the user's channel is full → the write is dropped and
  sse-gen's connection removed as stale"
 (def ^:private full-ch (chan 1))
 (put! full-ch ::earlier-write)
 (reset! !user-chans {"u1" {:chan full-ch}})
 (with-redefs [traced-write                  (spy/spy (constantly ::traced-write))
               remove-connection-if-current! (spy/spy (constantly true))]
   (enqueue-write! "cid-1" "u1" ::sse-1 (fn [] :written)) := true
   (spy/called-once-with? remove-connection-if-current! "cid-1" "u1" ::sse-1) := true)
 (<!! full-ch) := ::earlier-write
 (clojure.core.async/poll! full-ch) := nil
 (reset! !user-chans {})

 "enqueue-write!: no channel for the user → nothing is enqueued"
 (reset! !user-chans {})
 (with-redefs [traced-write                  (spy/spy)
               remove-connection-if-current! (spy/spy)]
   (enqueue-write! "cid-1" "u1" ::sse-1 (fn [] :written)) := nil
   (spy/not-called? traced-write) := true
   (spy/not-called? remove-connection-if-current!) := true))

(defn register-connection!
  "Registers sse-gen (and its component flow, when given) under
   (component-id, user-id). A reconnect replaces the previous entry; the
   replaced entry's flow is stopped here, since its stream's late on-close
   will no longer match the registry."
  ([component component-id sse-gen logged-user]
   (register-connection! component component-id sse-gen logged-user nil))
  ([_component component-id sse-gen logged-user component-flow]
   (let [user-id (:user/id logged-user)
         _       (ensure-user-chan! user-id)
         [old _] (swap-vals! !connections assoc-in [component-id user-id]
                             {:sse-gen sse-gen :flow component-flow})
         prior   (get-in old [component-id user-id])]
     (when (and prior (not (identical? (:flow prior) component-flow)))
       (stop-flow-of! prior))
     nil)))

(defn unregister-connection! [component component-id sse-gen logged-user]
  (let [user-id (:user/id logged-user)]
    (when (remove-connection-if-current! component-id user-id sse-gen)
      (when-not (user-has-connections? user-id @!connections)
        (destroy-user-chan! user-id)))))

(tests
 "register-connection!: a first connect registers the stream and its flow, and
  ensures the user's write channel"
 (reset! !connections {})
 (with-redefs [ensure-user-chan! (spy/spy)
               stop-flow-of!     (spy/spy)]
   (register-connection! :comp "cid-1" ::sse-a {:user/id "user-a"} ::flow-a) := nil
   @!connections := {"cid-1" {"user-a" {:sse-gen ::sse-a :flow ::flow-a}}}
   (spy/called-once-with? ensure-user-chan! "user-a") := true
   (spy/not-called? stop-flow-of!) := true)

 "register-connection!: without a flow (4 args) the entry's :flow is nil"
 (reset! !connections {})
 (with-redefs [ensure-user-chan! (spy/spy)
               stop-flow-of!     (spy/spy)]
   (register-connection! :comp "cid-1" ::sse-a {:user/id "user-a"}) := nil
   @!connections := {"cid-1" {"user-a" {:sse-gen ::sse-a :flow nil}}})

 "register-connection!: a reconnect (a new stream under the same key) replaces
  the entry and stops the replaced entry's flow"
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-old :flow ::flow-old}}})
 (with-redefs [ensure-user-chan! (spy/spy)
               stop-flow-of!     (spy/spy)]
   (register-connection! :comp "cid-1" ::sse-new {:user/id "user-a"} ::flow-new) := nil
   @!connections := {"cid-1" {"user-a" {:sse-gen ::sse-new :flow ::flow-new}}}
   (spy/called-once-with? stop-flow-of! {:sse-gen ::sse-old :flow ::flow-old}) := true)

 "register-connection!: re-registered with the same flow → that flow keeps running"
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-old :flow ::flow-a}}})
 (with-redefs [ensure-user-chan! (spy/spy)
               stop-flow-of!     (spy/spy)]
   (register-connection! :comp "cid-1" ::sse-new {:user/id "user-a"} ::flow-a) := nil
   (spy/not-called? stop-flow-of!) := true)
 (reset! !connections {}))

(tests
 "unregister-connection!: the user's current stream, their last one → the entry
  is removed and the user's write channel destroyed"
 (reset! !connections {"cid-1" {"user-b" {:sse-gen ::sse-b}}})
 (with-redefs [remove-connection-if-current! (spy/spy (constantly true))
               user-has-connections?         (spy/spy (constantly false))
               destroy-user-chan!            (spy/spy)]
   (unregister-connection! :comp "cid-1" ::sse-a {:user/id "user-a"}) := nil
   (spy/called-once-with? remove-connection-if-current! "cid-1" "user-a" ::sse-a) := true
   (spy/called-once-with? user-has-connections? "user-a" {"cid-1" {"user-b" {:sse-gen ::sse-b}}}) := true
   (spy/called-once-with? destroy-user-chan! "user-a") := true)

 "unregister-connection!: the user still has a stream under another
  component-id → the write channel is kept alive"
 (reset! !connections {"cid-1" {"user-b" {:sse-gen ::sse-b}}})
 (with-redefs [remove-connection-if-current! (spy/spy (constantly true))
               user-has-connections?         (spy/spy (constantly true))
               destroy-user-chan!            (spy/spy)]
   (unregister-connection! :comp "cid-1" ::sse-a {:user/id "user-a"}) := nil
   (spy/not-called? destroy-user-chan!) := true)

 "unregister-connection!: a stale stream (the old one's late on-close after a
  reconnect) removes nothing → the user's write channel survives"
 (reset! !connections {"cid-1" {"user-b" {:sse-gen ::sse-b}}})
 (with-redefs [remove-connection-if-current! (spy/spy (constantly false))
               user-has-connections?         (spy/spy)
               destroy-user-chan!            (spy/spy)]
   (unregister-connection! :comp "cid-1" ::sse-old {:user/id "user-a"}) := nil
   (spy/called-once-with? remove-connection-if-current! "cid-1" "user-a" ::sse-old) := true
   (spy/not-called? user-has-connections?) := true
   (spy/not-called? destroy-user-chan!) := true)
 (reset! !connections {}))

;;; Send infrastructure

(defn send-replies!
  "Dispatches a seq of component-responses to a single SSE client.
   Each response is a vector like [:patch elements options].
   Writes are enqueued onto the user's channel for non-blocking delivery.

   Effect types:
   - :patch    — send to this specific user only
   - :broadcast — send to ALL users currently connected under component-id"
  [component-id user-id sse-gen component-responses]
  (let [valid-component-responses (filter first component-responses)]
    (doseq [[effect-name & effect-args] valid-component-responses]
      (case effect-name
        :patch
        (let [[elements options] effect-args]
          (enqueue-write! component-id user-id sse-gen
                          (fn []
                            (try
                              (send-effect! sse-gen [:patch elements (or options {})])
                              (catch Throwable e
                                (log/trace ::patch-write-failed
                                           [:component-id component-id
                                            :user-id user-id
                                            :error (.getMessage e)]
                                           nil)
                                (remove-connection-if-current! component-id user-id sse-gen))))))

        :broadcast
        (let [[elements options] effect-args
              user-sse-pairs (map (fn [[uid entry]] [uid (:sse-gen entry)])
                                  (get @!connections component-id))]
          (doseq [[uid usse-gen] user-sse-pairs]
            (enqueue-write! component-id uid usse-gen
                            (fn []
                              (try
                                (send-effect! usse-gen [:patch elements (or options {})])
                                (catch Throwable e
                                  (log/trace ::broadcast-write-failed
                                             [:component-id component-id
                                              :user-id uid
                                              :error (.getMessage e)]
                                             nil)
                                  (remove-connection-if-current! component-id uid usse-gen)))))))

        ;; Default case - log unknown effect
        (log/trace ::unknown-effect
                   [:effect-name effect-name
                    :effect-args effect-args]
                   nil)))))

(tests
 (defn- run-enqueued-writes!
   "Runs the write thunks an `enqueue-write!` spy received, as the user's
    consumer thread would."
   [enqueue-spy]
   (doseq [[_ _ _ write-fn] (spy/calls enqueue-spy)]
     (write-fn)))

 "send-replies!: a :patch is enqueued on that user's write channel only"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}})
 (with-redefs [enqueue-write! (spy/spy)]
   (send-replies! "cid-1" "u1" ::sse1 [[:patch [:div "hi"] {:d*/selector "#msgs"}]]) := nil
   (spy/calls enqueue-write!) := [["cid-1" "u1" ::sse1 _]])

 "send-replies!: the :patch write sends the elements to that user's stream"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}})
 (with-redefs [enqueue-write! (spy/spy)
               send-effect!   (spy/spy)]
   (send-replies! "cid-1" "u1" ::sse1 [[:patch [:div "hi"] {:d*/selector "#msgs"}]])
   (run-enqueued-writes! enqueue-write!)
   (spy/called-once-with? send-effect! ::sse1 [:patch [:div "hi"] {:d*/selector "#msgs"}]) := true)

 "send-replies!: a :patch write that fails removes the connection of THE
  sse-gen it was written to — identity-guarded, so a failure on a STALE
  sse-gen leaves a newer registered one in place (remove-connection-if-current!)"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}})
 (with-redefs [enqueue-write!                (spy/spy)
               send-effect!                  (fn [_ _] (throw (ex-info "Broken pipe" {})))
               remove-connection-if-current! (spy/spy)]
   (send-replies! "cid-1" "u1" ::sse-old [[:patch [:div "msg"] {}]])
   (run-enqueued-writes! enqueue-write!)
   (spy/called-once-with? remove-connection-if-current! "cid-1" "u1" ::sse-old) := true)

 "send-replies!: a :broadcast is enqueued for every user connected under the
  component-id, each write going to that user's own stream"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}
                       "cid-2" {"u3" {:sse-gen ::sse3}}})
 (with-redefs [enqueue-write! (spy/spy)
               send-effect!   (spy/spy)]
   (send-replies! "cid-1" "u1" ::sse1 [[:broadcast [:div "all"] {}]])
   (run-enqueued-writes! enqueue-write!)
   (->> (spy/calls enqueue-write!)
        (map (fn [[cid uid sse-gen _]] [cid uid sse-gen]))
        set)
   := #{["cid-1" "u1" ::sse1] ["cid-1" "u2" ::sse2]}
   (set (spy/calls send-effect!))
   := #{[::sse1 [:patch [:div "all"] {}]] [::sse2 [:patch [:div "all"] {}]]})
 (reset! !connections {}))

;;; Async UI channel – push updates after the initial SSE response

(defn handle-ui-event
  "Handles a single message from !ui-chan.
   Expects [component-id user-id component-responses mulog-ctx] where
   component-responses is a seq of effect vectors like [[:patch [:div ...] opts]]
   and mulog-ctx (optional) is the caller's mulog context captured by send-ui!,
   re-established here so the per-write `::sse-write` spans nest under the
   caller's trace (the agent turn) instead of being context-less."
  [[component-id user-id component-responses mulog-ctx]]
  (when-let [sse-gen (sse-gen-of component-id user-id)]
    (log/with-context (or mulog-ctx {})
      (send-replies! component-id user-id sse-gen component-responses))))

(defn send-ui!
  "Puts component-responses onto !ui-chan for async delivery to the specific
   SSE client identified by component-id and user-id.
   component-responses is a seq of effect vectors,
   e.g. [[:patch [:div#foo \"hello\"] {:d*/selector \"#foo\"}]].
   The caller's mulog context rides along: the channel hop would otherwise
   drop it and every write would trace as its own root."
  [component-id user-id component-responses]
  (put! !ui-chan [component-id user-id component-responses (log/local-context)]))

(tests
 "handle-ui-event: hands the responses to send-replies! for that user's stream"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}})
 (with-redefs [send-replies! (spy/spy (constantly ::sent))]
   (handle-ui-event ["cid-1" "u1" [[:patch [:div "x"] {}]]]) := ::sent
   (spy/called-once-with? send-replies! "cid-1" "u1" ::sse1 [[:patch [:div "x"] {}]]) := true)

 "handle-ui-event: re-establishes the mulog context send-ui! captured, so
  send-replies! (and the write thunks it enqueues) run inside the caller's
  trace rather than context-less"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}})
 ;; the stub answers with the :turn of the context it was called under
 (with-redefs [send-replies! (spy/spy (fn [& _] (:turn (com.brunobonacci.mulog/local-context))))]
   (handle-ui-event ["cid-1" "u1" [[:patch [:div "x"] {}]] {:mulog/root-trace :root-9 :turn "t9"}]) := "t9"
   (spy/called-once-with? send-replies! "cid-1" "u1" ::sse1 [[:patch [:div "x"] {}]]) := true)

 "handle-ui-event: a legacy 3-element message (no ctx) still works, under an
  empty context"
 (reset! !connections {"cid-1" {"u1" {:sse-gen ::sse1} "u2" {:sse-gen ::sse2}}})
 (with-redefs [send-replies! (spy/spy (fn [& _] (:turn (com.brunobonacci.mulog/local-context))))]
   (handle-ui-event ["cid-1" "u1" [[:patch [:div "x"] {}]]]) := nil
   (spy/called-once-with? send-replies! "cid-1" "u1" ::sse1 [[:patch [:div "x"] {}]]) := true)

 "handle-ui-event: a no-op when the user has no connection"
 (reset! !connections {})
 (with-redefs [send-replies! (spy/spy)]
   (handle-ui-event ["no-such-cid" "no-such-user" [[:patch [:div] {}]]]) := nil
   (spy/not-called? send-replies!) := true)

 "send-ui!: puts the message on !ui-chan with the caller's mulog context alongside"
 (with-redefs [put!              (spy/spy (constantly true))
               log/local-context (constantly {:mulog/root-trace :root-3})]
   (send-ui! "cid-1" "u1" [[:patch [:div] {}]]) := true
   (spy/called-once-with? put! !ui-chan ["cid-1" "u1" [[:patch [:div] {}]] {:mulog/root-trace :root-3}])
   := true))

(comment

  ;; Push a patch to a specific user:
  (send-ui! "todo-chat-42" "user-123"
            [[:patch
              [:div#chat-messages [:p "New message from AI"]]
              {:d*/selector "#chat-messages"
               :d*/patch-mode :d*/pm-append}]])

  ;; Broadcast a patch to ALL users connected under a component-id
  ;; (use :broadcast effect — handled by send-replies! fan-out):
  (send-ui! "counter-42" "user-123"
            [[:broadcast
              [:div#counter [:span "42"]]
              {:d*/selector "#counter"}]])

  nil)

;;; Response type detection

(defn- component-responses?
  "Returns true if the component returned a list of effects (like chat_simulator2)
   rather than plain hiccup. Effects are a seq of vectors: [[:effect-name ...] ...]."
  [html]
  (and (vector? html) (vector? (first html))))

(defn- send-html-to-sse!
  "Sends pre-computed HTML to a single sse-gen.
   If the html is component-responses (effects), dispatches all effects
   via send-effect!. If plain hiccup, sends directly via d*/patch-elements!."
  [{:keys [sse-gen html]}]
  (if (component-responses? html)
    (doseq [[effect-type :as effect] html]
      (case effect-type
        (:patch :patch-signals :execute-script)
        (send-effect! sse-gen effect)
        (log/trace ::unknown-sse-effect [:effect-type effect-type] nil)))
    (d*/patch-elements! sse-gen (h/html (hc/compile html)) {})))

(tests
 "component-responses?: a vector of effect vectors is an effects list"
 (component-responses? [[:patch [:div] {}]]) := true

 "component-responses?: plain hiccup is not"
 (component-responses? [:div "simple"]) := false

 "send-html-to-sse! dispatches :patch and :patch-signals via send-effect!, in order"
 (with-redefs [send-effect! (spy/spy)]
   (send-html-to-sse!
    {:sse-gen ::mock-sse
     :html [[:patch [:div#foo "hello"] {:d*/selector "#foo"}]
            [:patch-signals {:counter 1} {}]]})
   (spy/calls send-effect!)
   := [[::mock-sse [:patch [:div#foo "hello"] {:d*/selector "#foo"}]]
       [::mock-sse [:patch-signals {:counter 1} {}]]])

 "send-html-to-sse! sends plain hiccup, rendered to HTML, directly via d*/patch-elements!"
 (with-redefs [d*/patch-elements! (spy/spy)
               send-effect!       (spy/spy)]
   (send-html-to-sse!
    {:sse-gen ::mock-sse
     :html [:div "simple"]})
   (spy/called-once-with? d*/patch-elements! ::mock-sse "<div>simple</div>" {}) := true
   (spy/not-called? send-effect!) := true))

;;; The connection's component flow

(defn- handle-flow-render!
  "What the drain loop does with one render from a connection's flow:
   - it carries a `:reply` → delivered to the caller waiting on it (the
     connect's INIT, a POST); that caller answers with it, so it is NOT
     pushed down the stream;
   - an error (`common/error-of`) without a reply (a server event failed) → logged; the
     flow stays up;
   - otherwise (a server `send-event!`) → enqueued on the user's ordered write
     channel, under the sender's trace."
  [component-id user-id sse-gen {:keys [reply html mulog-ctx] :as render}]
  (cond
    reply
    (deliver reply render)

    (common/error-of render)
    (log/with-context (or mulog-ctx {})
      (log/trace ::flow-event-error
                 [:component-id component-id
                  :user-id user-id
                  :error (ex-message (common/error-of render))]
                 nil))

    :else
    (log/with-context (or mulog-ctx {})
      (enqueue-write! component-id user-id sse-gen
                      (fn []
                        (try
                          (send-html-to-sse! {:sse-gen sse-gen :html html})
                          (catch Throwable e
                            (log/trace ::flow-write-failed
                                       [:component-id component-id
                                        :user-id user-id
                                        :error (.getMessage e)]
                                       nil)
                            (remove-connection-if-current! component-id user-id sse-gen))))))))

(tests
 (defn- run-enqueued-writes!
   "Runs the write thunks an `enqueue-write!` spy received, as the user's
    consumer thread would."
   [enqueue-spy]
   (doseq [[_ _ _ write-fn] (spy/calls enqueue-spy)]
     (write-fn)))
 "handle-flow-render!: a render with a :reply is delivered to the caller
  waiting on it (the connect's INIT, a POST) — never pushed down the stream"
 (def ^:private waiting (promise))
 (with-redefs [enqueue-write! (spy/spy)]
   (handle-flow-render! "cid-1" "u1" ::sse1 {:html [:p "2"] :reply waiting})
   (spy/not-called? enqueue-write!) := true)
 (deref waiting 100 ::not-delivered) := {:html [:p "2"] :reply waiting}

 "handle-flow-render!: an error without a reply (a server event failed) is
  logged; nothing is pushed"
 (with-redefs [enqueue-write! (spy/spy)]
   (handle-flow-render! "cid-1" "u1" ::sse1 (common/error (ex-info "boom" {}) {}))
   (spy/not-called? enqueue-write!) := true)

 "handle-flow-render!: any other render (a server send-event!) is enqueued on
  the user's write channel"
 (with-redefs [enqueue-write! (spy/spy)]
   (handle-flow-render! "cid-1" "u1" ::sse1 {:html [:p "1"]})
   (spy/calls enqueue-write!) := [["cid-1" "u1" ::sse1 _]])

 "handle-flow-render!: that write sends the html down the stream"
 (with-redefs [enqueue-write!    (spy/spy)
               send-html-to-sse! (spy/spy)]
   (handle-flow-render! "cid-1" "u1" ::sse1 {:html [:p "1"]})
   (run-enqueued-writes! enqueue-write!)
   (spy/called-once-with? send-html-to-sse! {:sse-gen ::sse1 :html [:p "1"]}) := true)

 "handle-flow-render!: a write that fails removes that sse-gen's connection"
 (with-redefs [enqueue-write!                (spy/spy)
               send-html-to-sse!             (fn [_] (throw (ex-info "Broken pipe" {})))
               remove-connection-if-current! (spy/spy)]
   (handle-flow-render! "cid-1" "u1" ::sse1 {:html [:p "1"]})
   (run-enqueued-writes! enqueue-write!)
   (spy/called-once-with? remove-connection-if-current! "cid-1" "u1" ::sse1) := true))

(defn- start-drain!
  "The single consumer of a connection flow's out-chan, on a virtual thread.
   Ends when the flow is stopped (its out-chan closes)."
  [component-id user-id sse-gen {:keys [out-chan]}]
  (Thread/startVirtualThread
   (fn []
     (loop []
       (when-some [render (<!! out-chan)]
         (try
           (handle-flow-render! component-id user-id sse-gen render)
           (catch Throwable e
             (log/trace ::flow-drain-error
                        [:component-id component-id :user-id user-id :error (.getMessage e)]
                        nil)))
         (recur))))))

(tests
 "start-drain!: hands every render on the flow's out-chan to
  handle-flow-render!, keeps going after one throws, and ends when the
  out-chan closes (the flow stopped)"
 (def ^:private drained-out (chan 10))
 (put! drained-out {:html :first})
 (put! drained-out {:html :second})
 (close! drained-out)
 (with-redefs [handle-flow-render! (spy/spy (fn [_ _ _ render]
                                              (when (= :first (:html render))
                                                (throw (ex-info "boom" {})))))]
   (def ^:private drain (start-drain! "cid-1" "u1" ::sse1 {:out-chan drained-out}))
   (.join ^Thread drain 1000)
   (.isAlive ^Thread drain) := false
   (spy/calls handle-flow-render!)
   := [["cid-1" "u1" ::sse1 {:html :first}]
       ["cid-1" "u1" ::sse1 {:html :second}]]))

(defn- run-through-flow!
  "Puts the request's event on the connection flow's in-chan with a reply
   promise and waits for its render (see `process-event/await-render`). Returns nil
   when the flow's in-chan is already closed."
  [{:keys [in-chan]} req component component-id event-name]
  (let [reply (promise)]
    (when (put! in-chan (assoc (request/->event-msg req component-id event-name) :reply reply))
      (process-event/await-render reply component event-name))))

(tests
 "run-through-flow!: puts the request's event, with a reply promise, on the
  flow's in-chan and waits for its render"
 (def ^:private open-in (chan 1))
 (with-redefs [request/->event-msg        (spy/spy (constantly {:event ["bump" {}]}))
               process-event/await-render (spy/spy (constantly {:html [:p "1"]}))]
   (run-through-flow! {:in-chan open-in}
                      {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                       :params      {:logged-user {:user/id "u1"}}}
                      :cc "cc1" "bump")
   := {:html [:p "1"]}
   (spy/called-once-with? request/->event-msg
                          {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                           :params      {:logged-user {:user/id "u1"}}}
                          "cc1" "bump")
   := true
   (def ^:private queued (clojure.core.async/poll! open-in))
   (dissoc queued :reply) := {:event ["bump" {}]}
   (spy/called-once-with? process-event/await-render (:reply queued) :cc "bump") := true)

 "run-through-flow!: the flow's in-chan is already closed → nil, nothing awaited"
 (def ^:private closed-in (chan 1))
 (close! closed-in)
 (with-redefs [request/->event-msg        (spy/spy (constantly {:event ["bump" {}]}))
               process-event/await-render (spy/spy)]
   (run-through-flow! {:in-chan closed-in}
                      {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                       :params      {:logged-user {:user/id "u1"}}}
                      :cc "cc1" "bump")
   := nil
   (spy/not-called? process-event/await-render) := true))

(defn- open-flow!
  "on-open's work: start the component's long-lived flow, register it with
   the stream, start draining it, then run the connect's event (INIT) through
   it and send that render down the stream."
  [req component component-id event-name sse-gen logged-user]
  (let [user-id (:user/id logged-user)
        cf      (process-event/start-flow!
                 (assoc (discovery/resolve-component component nil)
                        :component-name component
                        :component-id component-id
                        :keep-ctx? true))]
    (log/trace
     ::connection-opened
     [:component component
      :component-id component-id
      :event event-name]
     (register-connection! component component-id sse-gen logged-user cf))
    (start-drain! component-id user-id sse-gen cf)
    (some-> (run-through-flow! cf req component component-id event-name)
            (assoc :sse-gen sse-gen)
            send-html-to-sse!)))

(tests
 "open-flow!: starts the component's flow (holding its ctx), registers it with
  the stream, starts draining it, runs the connect's event (INIT) through it
  and sends that render down the stream"
 (with-redefs [discovery/resolve-component (spy/spy (constantly {:machine-var ::machine :component-fn-var ::view}))
               process-event/start-flow!   (spy/spy (constantly ::flow))
               register-connection!        (spy/spy)
               start-drain!                (spy/spy)
               run-through-flow!           (spy/spy (constantly {:html [:p "0"]}))
               send-html-to-sse!           (spy/spy (constantly ::sent))]
   (open-flow! {:params {:logged-user {:user/id "u1"}}} :cc "cc1" "INIT" ::sse-1 {:user/id "u1"}) := ::sent
   (spy/called-once-with? discovery/resolve-component :cc nil) := true
   (spy/called-once-with? process-event/start-flow!
                          {:machine-var    ::machine
                           :component-fn-var ::view
                           :component-name :cc
                           :component-id   "cc1"
                           :keep-ctx?      true})
   := true
   (spy/called-once-with? register-connection! :cc "cc1" ::sse-1 {:user/id "u1"} ::flow) := true
   (spy/called-once-with? start-drain! "cc1" "u1" ::sse-1 ::flow) := true
   (spy/called-once-with? run-through-flow! ::flow {:params {:logged-user {:user/id "u1"}}} :cc "cc1" "INIT") := true
   (spy/called-once-with? send-html-to-sse! {:html [:p "0"] :sse-gen ::sse-1}) := true)

 "open-flow!: no render for INIT (the flow's in-chan already closed) → nothing
  is sent down the stream"
 (with-redefs [discovery/resolve-component (spy/spy (constantly {:machine-var ::machine :component-fn-var ::view}))
               process-event/start-flow!   (spy/spy (constantly ::flow))
               register-connection!        (spy/spy)
               start-drain!                (spy/spy)
               run-through-flow!           (spy/spy (constantly nil))
               send-html-to-sse!           (spy/spy)]
   (open-flow! {:params {:logged-user {:user/id "u1"}}} :cc "cc1" "INIT" ::sse-1 {:user/id "u1"}) := nil
   (spy/not-called? send-html-to-sse!) := true))

(defn connections
  "The open streams, as {component-id [user-id …]} — who `send-ui!` and
   `send-event!` can reach right now. For the REPL and diagnostics."
  []
  (into {}
        (keep (fn [[component-id users]]
                (when (seq users) [component-id (vec (keys users))])))
        @!connections))

(tests
 "connections: component-id → the user-ids with an open stream; empty ids dropped"
 (with-redefs [!connections (atom {"cd1" {"alice" {:sse-gen ::a} "bob" {:sse-gen ::b}}
                                   "gone" {}})]
   (-> (connections)
       (update "cd1" sort))
   := {"cd1" ["alice" "bob"]}))

(defn send-event!
  "Puts a server event on the flow of the connection (component-id, user-id):
   it transitions from the ctx the flow holds, and its render is pushed down
   that connection's stream. The caller's trace rides along. Returns true when
   the event was queued, false when there is no open connection with a flow."
  [component-id user-id event-name params]
  (if-let [{:keys [in-chan]} (get-in @!connections [component-id user-id :flow])]
    (put! in-chan (common/server-event event-name params))
    false))

(tests
 "send-event!: puts the server event on the connection's flow; true when queued"
 (def ^:private event-in (chan 1))
 (reset! !connections {"cc1" {"u1" {:sse-gen ::sse-1 :flow {:in-chan event-in}}}})
 (with-redefs [common/server-event (spy/spy (constantly ::server-event))]
   (send-event! "cc1" "u1" "bump" {:by 1}) := true
   (spy/called-once-with? common/server-event "bump" {:by 1}) := true)
 (clojure.core.async/poll! event-in) := ::server-event

 "send-event!: no open connection (never opened, or closed) → false"
 (reset! !connections {})
 (send-event! "cc1" "u1" "bump" {}) := false

 "send-event!: a connection registered without a flow → false"
 (reset! !connections {"cc1" {"u1" {:sse-gen ::sse-1 :flow nil}}})
 (send-event! "cc1" "u1" "bump" {}) := false

 "send-event!: the flow is stopped (its in-chan closed) → false"
 (def ^:private stopped-in (chan 1))
 (close! stopped-in)
 (reset! !connections {"cc1" {"u1" {:sse-gen ::sse-1 :flow {:in-chan stopped-in}}}})
 (send-event! "cc1" "u1" "bump" {}) := false
 (reset! !connections {}))

;;; SSE connect handler

(defn handler-connected-component-connect [req respond _raise]
  (let [component (keyword (get-in req [:path-params :component]))
        component-id (get-in req [:path-params :component-id])
        event-name (get-in req [:path-params :event])
        logged-user (get-in req [:params :logged-user])
        ;; Observer bookkeeping for this stream: nil until the open is
        ;; reported, then its token — or ::closed if on-close won the race
        ;; against a slow initial render.
        !observed (atom nil)]
    (respond
     (->sse-response
      req
      {;; Defeat proxy response buffering on long-lived streams (nginx/Traefik
       ;; honour X-Accel-Buffering: no). Pairs with the heartbeat loop below.
       :headers {"X-Accel-Buffering" "no"}
       on-open
       (fn [sse-gen]
         (let [started (System/nanoTime)]
           (try
             ;; the initial render is this route's latency (the stream itself
             ;; lives for minutes)
             (observer/observe-event!
              :nds-connect component nil event-name
              #(open-flow! req component component-id event-name sse-gen logged-user))
             (finally
               ;; reported AFTER the pipeline so discovery has resolved the
               ;; label; a stream that already closed is opened+closed at once
               (when-let [token (observer/connection-opened! component started)]
                 (when-not (compare-and-set! !observed nil token)
                   (observer/connection-closed! token)))))))

       on-close
       (fn [sse-gen]
         (log/trace
          ::connection-closed
          [:component component
           :component-id component-id]
          (unregister-connection! component component-id sse-gen logged-user))
         (let [[token _] (swap-vals! !observed (constantly ::closed))]
           (when (map? token)
             (observer/connection-closed! token))))}))))

;;; Events for a connected component (POST on the connect path)

(defn- connected-event-response
  "Runs a browser event through the connection's flow — so it transitions from
   the ctx the flow holds — and answers with the render exactly like a default
   component does (HTML, or a one-shot SSE for an effects list). Without an
   open connection (stream closed, client reconnecting) it falls back to the
   default one-shot handler, so the click still gets a render."
  [req]
  (let [component    (keyword (get-in req [:path-params :component]))
        component-id (get-in req [:path-params :component-id])
        event-name   (get-in req [:path-params :event])
        user-id      (get-in req [:params :logged-user :user/id])
        cf           (get-in @!connections [component-id user-id :flow])
        result       (when cf
                       (observer/observe-event!
                        :nds-connect-event component nil event-name
                        (fn []
                          (try
                            (some-> (run-through-flow! cf req component component-id event-name)
                                    (assoc :request req)
                                    render-response)
                            (catch Exception e
                              (error-response e component component-id event-name nil))))))]
    (or result (default-handler/default-handler req))))

(defn handler-connected-component-event
  "Ring handler (both arities) for an event POSTed to a connected component's
   path (/ds/nds-connect/<component>/<component-id>/<event>): it runs through
   the flow that lives with the user's open stream."
  ([req] (connected-event-response req))
  ([req respond raise]
   (try
     (respond (connected-event-response req))
     (catch Exception e
       (raise e)))))

(tests
 (defn- observe-through [_route _component _controller _event f] (f))

 "connected-event-response: with an open connection the event runs through
  THAT connection's flow (so it transitions from the ctx the flow holds),
  inside an :nds-connect-event observation, and is answered with the render
  like a default component — never pushed down the stream"
 (reset! !connections {"cc1" {"u1" {:sse-gen ::sse-1 :flow ::flow}}})
 (with-redefs [observer/observe-event!         (spy/spy observe-through)
               run-through-flow!               (spy/spy (constantly {:html [:p "2"]}))
               render-response                 (spy/spy (constantly {:status 200 :body "<p>2</p>"}))
               default-handler/default-handler (spy/spy)]
   (connected-event-response {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                              :params      {:logged-user {:user/id "u1"}}})
   := {:status 200 :body "<p>2</p>"}
   (spy/calls observer/observe-event!) := [[:nds-connect-event :cc nil "bump" _]]
   (spy/called-once-with? run-through-flow!
                          ::flow
                          {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                           :params      {:logged-user {:user/id "u1"}}}
                          :cc "cc1" "bump")
   := true
   (spy/called-once-with? render-response
                          {:html    [:p "2"]
                           :request {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                                     :params      {:logged-user {:user/id "u1"}}}})
   := true
   (spy/not-called? default-handler/default-handler) := true)

 "connected-event-response: the flow throws → an error response for that event"
 (reset! !connections {"cc1" {"u1" {:sse-gen ::sse-1 :flow ::flow}}})
 (with-redefs [observer/observe-event! (spy/spy observe-through)
               run-through-flow!       (fn [& _] (throw (ex-info "boom" {})))
               error-response          (spy/spy (constantly {:status 500}))]
   (connected-event-response {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                              :params      {:logged-user {:user/id "u1"}}})
   := {:status 500}
   (-> error-response
       spy/last-call
       vec
       (update 0 ex-message))
   := ["boom" :cc "cc1" "bump" nil])

 "connected-event-response: no render (the flow's in-chan already closed) →
  falls back to the default one-shot handler"
 (reset! !connections {"cc1" {"u1" {:sse-gen ::sse-1 :flow ::flow}}})
 (with-redefs [observer/observe-event!         (spy/spy observe-through)
               run-through-flow!               (spy/spy (constantly nil))
               render-response                 (spy/spy)
               default-handler/default-handler (spy/spy (constantly ::one-shot))]
   (connected-event-response {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                              :params      {:logged-user {:user/id "u1"}}})
   := ::one-shot
   (spy/not-called? render-response) := true
   (spy/called-once-with? default-handler/default-handler
                          {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                           :params      {:logged-user {:user/id "u1"}}})
   := true)

 "connected-event-response: a POST with no open connection falls back to the
  default one-shot handler"
 (reset! !connections {})
 (with-redefs [run-through-flow!               (spy/spy)
               default-handler/default-handler (spy/spy (constantly ::one-shot))]
   (connected-event-response {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                              :params      {:logged-user {:user/id "u1"}}})
   := ::one-shot
   (spy/not-called? run-through-flow!) := true
   (spy/called-once-with? default-handler/default-handler
                          {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                           :params      {:logged-user {:user/id "u1"}}})
   := true)

 "handler-connected-component-event (1 arity): the connected event's response"
 (with-redefs [connected-event-response (spy/spy (constantly ::response))]
   (handler-connected-component-event {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                                       :params      {:logged-user {:user/id "u1"}}})
   := ::response
   (spy/called-once-with? connected-event-response
                          {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                           :params      {:logged-user {:user/id "u1"}}})
   := true)

 "handler-connected-component-event (3 arities): the response goes to respond"
 (def ^:private respond-ok (spy/spy))
 (def ^:private raise-ok (spy/spy))
 (with-redefs [connected-event-response (spy/spy (constantly ::response))]
   (handler-connected-component-event {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                                       :params      {:logged-user {:user/id "u1"}}}
                                      respond-ok
                                      raise-ok)
   (spy/called-once-with? connected-event-response
                          {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                           :params      {:logged-user {:user/id "u1"}}})
   := true
   (spy/called-once-with? respond-ok ::response) := true
   (spy/not-called? raise-ok) := true)

 "handler-connected-component-event (3 arities): an exception goes to raise"
 (def ^:private failure (ex-info "boom" {}))
 (def ^:private respond-failed (spy/spy))
 (def ^:private raise-failed (spy/spy))
 (with-redefs [connected-event-response (fn [_] (throw failure))]
   (handler-connected-component-event {:path-params {:component "cc" :component-id "cc1" :event "bump"}
                                       :params      {:logged-user {:user/id "u1"}}}
                                      respond-failed
                                      raise-failed)
   (spy/not-called? respond-failed) := true
   (spy/called-once-with? raise-failed failure) := true)
 (reset! !connections {}))

(tests
 (defn- connect-opts
   "Runs the connect handler on req with the SSE adapter stubbed; returns the
    options map it handed to ->sse-response (on-open / on-close inside)."
   [req]
   (with-redefs [->sse-response (fn [_req opts] opts)]
     (handler-connected-component-connect req identity identity)))
 (defn- observe-connect [_route _component _controller _event f] (f))

 "handler-connected-component-connect: answers with an SSE stream that defeats
  proxy response buffering"
 (-> (connect-opts {:path-params {:component "home.home" :component-id "c1" :event "INIT"}
                    :params      {:logged-user {:user/id "u1"}}})
     :headers)
 := {"X-Accel-Buffering" "no"}

 "on-open: the initial render (open-flow!) runs inside an :nds-connect event
  observation; the stream is then reported opened, on the component's label"
 (with-redefs [observer/observe-event!     (spy/spy observe-connect)
               open-flow!                  (spy/spy (constantly ::sent))
               observer/connection-opened! (spy/spy (constantly {:component "home.home" :started 0}))
               observer/connection-closed! (spy/spy)]
   ((get (connect-opts {:path-params {:component "home.home" :component-id "c1" :event "INIT"}
                        :params      {:logged-user {:user/id "u1"}}}) on-open) ::sse) := ::sent
   (spy/calls observer/observe-event!) := [[:nds-connect :home.home nil "INIT" _]]
   (spy/called-once-with? open-flow!
                          {:path-params {:component "home.home" :component-id "c1" :event "INIT"}
                           :params      {:logged-user {:user/id "u1"}}}
                          :home.home "c1" "INIT" ::sse {:user/id "u1"})
   := true
   (spy/calls observer/connection-opened!) := [[:home.home _]]
   (spy/not-called? observer/connection-closed!) := true)

 "on-close: unregisters the stream, and reports it closed with the token it
  was opened under — once, however many times on-close fires"
 (with-redefs [observer/observe-event!     (spy/spy observe-connect)
               open-flow!                  (spy/spy (constantly ::sent))
               unregister-connection!      (spy/spy)
               observer/connection-opened! (spy/spy (constantly {:component "home.home" :started 0}))
               observer/connection-closed! (spy/spy)]
   (def ^:private open-close-opts (connect-opts {:path-params {:component "home.home" :component-id "c1" :event "INIT"}
                                                 :params      {:logged-user {:user/id "u1"}}}))
   ((get open-close-opts on-open) ::sse)
   ((get open-close-opts on-close) ::sse)
   ((get open-close-opts on-close) ::sse)
   (spy/calls unregister-connection!)
   := [[:home.home "c1" ::sse {:user/id "u1"}]
       [:home.home "c1" ::sse {:user/id "u1"}]]
   (spy/called-once-with? observer/connection-closed! {:component "home.home" :started 0}) := true)

 "a stream that closes DURING the initial render is still opened+closed
  exactly once (the gauge never leaks)"
 (def ^:private !race-opts (promise))
 (with-redefs [observer/observe-event!     (spy/spy observe-connect)
               open-flow!                  (fn [& _] ((get @!race-opts on-close) ::sse) ::sent)
               unregister-connection!      (spy/spy)
               observer/connection-opened! (spy/spy (constantly {:component "home.home" :started 0}))
               observer/connection-closed! (spy/spy)]
   (deliver !race-opts (connect-opts {:path-params {:component "home.home" :component-id "c1" :event "INIT"}
                                      :params      {:logged-user {:user/id "u1"}}}))
   ((get @!race-opts on-open) ::sse) := ::sent
   (spy/called-once? observer/connection-opened!) := true
   (spy/called-once-with? observer/connection-closed! {:component "home.home" :started 0}) := true))

(defonce ui-loop-started
  (Thread/startVirtualThread
   (fn []
     (loop []
       (when-let [msg (<!! !ui-chan)]
         (try
           (handle-ui-event msg)
           (catch Throwable e
             (log/trace ::ui-loop-error
                        [:error (.getMessage e)
                         :type (str (type e))
                         :component-id (first msg)]
                        nil)))
         (recur))))))

;;; Heartbeat — keep long-lived SSE connections warm through proxies.
;; A persistent nds-connect stream that sits idle after its initial render can
;; be reaped by an edge proxy (observed Safari-only on prod, which adopts the
;; advertised HTTP/3 alt-svc). We periodically send an inert `patch-signals {}`
;; — a no-op merge on the client — so bytes keep flowing and the proxy keeps
;; the stream open. Well under typical proxy idle timeouts (30–60s).

(def heartbeat-interval-ms 15000)

(defn send-heartbeat!
  "Enqueues an inert keep-alive write to every open connection, reusing the
   per-user write channel + stale-drop semantics of send-replies!."
  []
  (doseq [[component-id user-map]   @!connections
          [user-id {:keys [sse-gen]}] user-map]
    (enqueue-write! component-id user-id sse-gen
                    (fn []
                      (try
                        (send-effect! sse-gen [:patch-signals {} {}])
                        (catch Throwable e
                          (log/trace ::heartbeat-write-failed
                                     [:component-id component-id
                                      :user-id user-id
                                      :error (.getMessage e)]
                                     nil)
                          (remove-connection-if-current! component-id user-id sse-gen)))))))

(tests
 (defn- run-enqueued-writes!
   "Runs the write thunks an `enqueue-write!` spy received, as the user's
    consumer thread would."
   [enqueue-spy]
   (doseq [[_ _ _ write-fn] (spy/calls enqueue-spy)]
     (write-fn)))

 "send-heartbeat!: one write enqueued per open connection"
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-a}}
                       "cid-2" {"user-b" {:sse-gen ::sse-b}}})
 (with-redefs [enqueue-write! (spy/spy)]
   (send-heartbeat!) := nil
   ;; each [cid uid sse-gen write-fn]
   (spy/calls enqueue-write!) := [["cid-1" "user-a" ::sse-a _] ["cid-2" "user-b" ::sse-b _]])

 "send-heartbeat!: each write sends an inert empty patch-signals"
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-a}}
                       "cid-2" {"user-b" {:sse-gen ::sse-b}}})
 (with-redefs [enqueue-write! (spy/spy)
               send-effect!   (spy/spy)]
   (send-heartbeat!)
   (run-enqueued-writes! enqueue-write!)
   (spy/calls send-effect!) := [[::sse-a [:patch-signals {} {}]] [::sse-b [:patch-signals {} {}]]])

 "send-heartbeat!: a heartbeat write that fails removes that stream's connection"
 (reset! !connections {"cid-1" {"user-a" {:sse-gen ::sse-a}}})
 (with-redefs [enqueue-write!                (spy/spy)
               send-effect!                  (fn [_ _] (throw (ex-info "Broken pipe" {})))
               remove-connection-if-current! (spy/spy)]
   (send-heartbeat!)
   (run-enqueued-writes! enqueue-write!)
   (spy/called-once-with? remove-connection-if-current! "cid-1" "user-a" ::sse-a) := true)
 (reset! !connections {}))

(defonce heartbeat-loop-started
  (Thread/startVirtualThread
   (fn []
     (loop []
       (try
         (Thread/sleep heartbeat-interval-ms)
         (send-heartbeat!)
         (catch Throwable e
           (log/trace ::heartbeat-loop-error [:error (.getMessage e)] nil)))
       (recur)))))

;;; Tests — ui-loop resilience

(tests
 "ui-loop continues processing after handle-ui-event throws. (The real loop is
  a defonce reading the def'd !ui-chan, so a ns reload in a live REPL leaves it
  on the old chan: this case drives the same loop shape on a chan of its own.)"
 (def ^:private test-ui-chan (chan 10))
 (def ^:private ui-handler
   (spy/spy (fn [[component-id :as m]]
              (when (= "cid-1" component-id)
                (throw (ex-info "Simulated failure" {})))
              m)))
 (put! test-ui-chan ["cid-1" "u1" [[:patch [:div "fail"] {}]]])
 (put! test-ui-chan ["cid-2" "u2" [[:patch [:div "ok"] {}]]])
 (close! test-ui-chan)
 (def ^:private test-ui-loop
   (Thread/startVirtualThread
    (fn []
      (loop []
        (when-let [msg (<!! test-ui-chan)]
          (try (ui-handler msg) (catch Throwable _e nil))
          (recur))))))
 (.join ^Thread test-ui-loop 1000)
 (spy/calls ui-handler)
 := [[["cid-1" "u1" [[:patch [:div "fail"] {}]]]]
     [["cid-2" "u2" [[:patch [:div "ok"] {}]]]]])
