(ns taia.ds.datastar.adapters.handlers.connected-component-handler
  "Handler for establishing persistent SSE connections to component instances.
   Manages a `!connections` atom that tracks open SSE connections per
   (component, component-id), enabling future server-push to connected clients.
   On connection open, runs the component pipeline and sends the initial render via SSE."
  (:require
   [taia.ds.logging.interface :as log]
   [taia.ds.datastar.adapters.handlers.observer :as observer]
   [taia.ds.datastar.adapters.handlers.pipeline :as pipeline]
   [taia.ds.datastar.adapters.handlers.response :refer [send-effect!]]
   [starfederation.datastar.clojure.adapter.ring :refer [->sse-response on-open on-close]]
   [starfederation.datastar.clojure.api :as d*]
   [dev.onionpancakes.chassis.compiler :as hc]
   [dev.onionpancakes.chassis.core :as h]
   [clojure.core.async :refer [chan close! <!! put! offer!]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

;; {component-id {user-id sse-gen }}
(def !connections (atom {}))

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

(defn- remove-connection-if-current!
  "Removes the (component-id, user-id) registry entry only if it still points
   at sse-gen. A client reconnect re-registers under the same key, so a late
   close/write-failure of the OLD stream must not evict the NEW live one.
   Returns true when the entry was removed."
  [component-id user-id sse-gen]
  (let [[old _new] (swap-vals! !connections
                               (fn [m]
                                 (if (identical? (get-in m [component-id user-id]) sse-gen)
                                   (update m component-id dissoc user-id)
                                   m)))]
    (identical? (get-in old [component-id user-id]) sse-gen)))

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

(defn register-connection! [component component-id sse-gen logged-user]
  (let [user-id (:user/id logged-user)]
    (ensure-user-chan! user-id)
    (swap! !connections assoc-in [component-id user-id] sse-gen)))

(defn unregister-connection! [component component-id sse-gen logged-user]
  (let [user-id (:user/id logged-user)]
    (when (remove-connection-if-current! component-id user-id sse-gen)
      (when-not (user-has-connections? user-id @!connections)
        (destroy-user-chan! user-id)))))

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
              user-sse-pairs (seq (get @!connections component-id))]
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

;;; Async UI channel – push updates after the initial SSE response

(defn handle-ui-event
  "Handles a single message from !ui-chan.
   Expects [component-id user-id component-responses mulog-ctx] where
   component-responses is a seq of effect vectors like [[:patch [:div ...] opts]]
   and mulog-ctx (optional) is the caller's mulog context captured by send-ui!,
   re-established here so the per-write `::sse-write` spans nest under the
   caller's trace (the agent turn) instead of being context-less."
  [[component-id user-id component-responses mulog-ctx]]
  (when-let [sse-gen (get-in @!connections [component-id user-id])]
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
 "send-html-to-sse! dispatches :patch and :patch-signals via send-effect!"
 (with-redefs [send-effect! (spy/spy)]
   (send-html-to-sse!
    {:sse-gen ::mock-sse
     :html [[:patch [:div#foo "hello"] {:d*/selector "#foo"}]
            [:patch-signals {:counter 1} {}]]})
   (spy/call-count send-effect!) := 2
   (first (spy/calls send-effect!))
   := [::mock-sse [:patch [:div#foo "hello"] {:d*/selector "#foo"}]]
   (second (spy/calls send-effect!))
   := [::mock-sse [:patch-signals {:counter 1} {}]])

 "send-html-to-sse! sends plain hiccup directly via d*/patch-elements!"
 (with-redefs [d*/patch-elements! (spy/spy)
               send-effect! (spy/spy)]
   (send-html-to-sse!
    {:sse-gen ::mock-sse
     :html [:div "simple"]})
   (spy/called-once? d*/patch-elements!) := true
   (spy/not-called? send-effect!) := true))

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
           (log/trace
            ::connection-opened
            [:component component
             :component-id component-id
             :event event-name]
            (register-connection! component component-id sse-gen logged-user))

           (try
             ;; the initial render is this route's latency (the stream itself
             ;; lives for minutes)
             (observer/observe-event!
              :nds-connect component nil event-name
              #(-> req
                   (assoc :sse-gen sse-gen)
                   (pipeline/process-component-event component component-id event-name nil)
                   (assoc :sse-gen sse-gen)
                   (assoc :component-id component-id)
                   (send-html-to-sse!)))
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

(def ^:private connect-req
  {:path-params {:component "home.home" :component-id "c1" :event "INIT"}
   :params      {:logged-user {:user/id "u1"}}})

(defn- connect-opts
  "Runs the connect handler with the SSE adapter stubbed; returns the
   options map it handed to ->sse-response (on-open / on-close inside)."
  []
  (with-redefs [->sse-response (fn [_req opts] opts)]
    (handler-connected-component-connect connect-req identity identity)))

(tests
 "nds-connect observations: the initial render is an :event, the stream an
  open/close pair on the same component label"
 (def prior-observer @@#'observer/!observer)
 (def connect-obs (spy/spy))
 (observer/set-observer! connect-obs)
 (with-redefs [register-connection!   (constantly nil)
               unregister-connection! (constantly nil)
               pipeline/process-component-event (fn [r & _] r)
               send-html-to-sse!      (constantly ::sent)
               observer/labels        (constantly {:component "home.home" :event "INIT"})]
   (def opts (connect-opts))
   ((get opts on-open) ::sse) := ::sent
   ((get opts on-close) ::sse)
   ((get opts on-close) ::sse))
 (mapv (comp (juxt :type :route :component) first) (spy/calls connect-obs))
 := [[:event :nds-connect "home.home"]
     [:sse-opened nil "home.home"]
     [:sse-closed nil "home.home"]]

 "nds-connect observations: a stream that closes DURING the initial render
  is still opened+closed exactly once (the gauge never leaks)"
 (def race-obs (spy/spy))
 (observer/set-observer! race-obs)
 (def !race-opts (promise))
 (with-redefs [register-connection!   (constantly nil)
               unregister-connection! (constantly nil)
               pipeline/process-component-event
               (fn [r & _] ((get @!race-opts on-close) ::sse) r)
               send-html-to-sse!      (constantly ::sent)
               observer/labels        (constantly {:component "home.home" :event "INIT"})]
   (deliver !race-opts (connect-opts))
   ((get @!race-opts on-open) ::sse))
 (mapv (comp :type first) (spy/calls race-obs)) := [:event :sse-opened :sse-closed]
 (observer/set-observer! prior-observer))

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
  (doseq [[component-id user-map] @!connections
          [user-id sse-gen]       user-map]
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

;;; Tests

;;; Tests — connection lifecycle

(tests
 "unregister-connection! removes user from connections map"
 (reset! !connections {"cid-1" {"user-a" ::sse-a "user-b" ::sse-b}})
 (reset! !user-chans {})
 ;; user-b still has a connection, so only user-a should be removed
 (ensure-user-chan! "user-a")
 (ensure-user-chan! "user-b")
 (unregister-connection! :comp "cid-1" ::sse-a {:user/id "user-a"})
 (get @!connections "cid-1") := {"user-b" ::sse-b}
 ;; user-a has no connections left, channel should be destroyed
 (get @!user-chans "user-a") := nil
 ;; user-b still connected, channel alive
 (some? (get @!user-chans "user-b")) := true
 (reset! !connections {})
 (destroy-user-chan! "user-b")
 (reset! !user-chans {}))

(tests
 "send-heartbeat! enqueues one inert patch-signals write per open connection"
 (reset! !connections {"cid-1" {"user-a" ::sse-a}
                       "cid-2" {"user-b" ::sse-b}})
 (with-redefs [enqueue-write! (spy/spy)]
   (send-heartbeat!)
   ;; one write enqueued per open connection, each [cid uid sse-gen write-fn]
   (spy/calls enqueue-write!) := [["cid-1" "user-a" ::sse-a _] ["cid-2" "user-b" ::sse-b _]]
   ;; each enqueued thunk sends an inert empty patch-signals
   (with-redefs [send-effect! (spy/spy)]
     (doseq [[_ _ _ write-fn] (spy/calls enqueue-write!)] (write-fn))
     (spy/calls send-effect!) := [[::sse-a [:patch-signals {} {}]] [::sse-b [:patch-signals {} {}]]]))
 (reset! !connections {}))

(tests
 "register-connection! creates user channel on first connect"
 (reset! !connections {})
 (reset! !user-chans {})
 (register-connection! :comp "cid-1" ::sse-a {:user/id "user-a"})
 ;; connection registered
 (get-in @!connections ["cid-1" "user-a"]) := ::sse-a
 ;; user channel created
 (some? (get-in @!user-chans ["user-a" :chan])) := true
 (reset! !connections {})
 (destroy-user-chan! "user-a")
 (reset! !user-chans {}))

(tests
 "reconnect race: a late close of the OLD stream must not evict the NEW one.
  Safari churns SSE connections (tab suspension, HTTP/3); the client
  reconnects under the same (component-id, user-id) key BEFORE Jetty notices
  the old socket died and fires its on-close."
 (reset! !connections {})
 (reset! !user-chans {})
 (register-connection! :comp "cid-1" ::sse-old {:user/id "user-a"})
 ;; client reconnects — new stream overwrites the registry entry
 (register-connection! :comp "cid-1" ::sse-new {:user/id "user-a"})
 ;; the old stream's on-close arrives late — must be a no-op
 (unregister-connection! :comp "cid-1" ::sse-old {:user/id "user-a"})
 (get-in @!connections ["cid-1" "user-a"]) := ::sse-new
 ;; the user's write channel survives too
 (some? (get-in @!user-chans ["user-a" :chan])) := true
 ;; closing the CURRENT stream still unregisters and destroys the channel
 (unregister-connection! :comp "cid-1" ::sse-new {:user/id "user-a"})
 (get-in @!connections ["cid-1" "user-a"]) := nil
 (get @!user-chans "user-a") := nil
 (reset! !connections {})
 (reset! !user-chans {}))

(tests
 "write-failure kick is identity-guarded: a failed write on a STALE sse-gen
  leaves a newer registered connection in place"
 (reset! !connections {"cid-1" {"u1" ::sse-new}})
 (reset! !user-chans {})
 (ensure-user-chan! "u1")
 ;; a patch enqueued for the OLD sse-gen fails — must not evict ::sse-new
 (with-redefs [send-effect! (fn [_ _] (throw (ex-info "Broken pipe" {})))]
   (send-replies! "cid-1" "u1" ::sse-old
                  [[:patch [:div "msg"] {}]])
   (Thread/sleep 100))
 (get-in @!connections ["cid-1" "u1"]) := ::sse-new
 ;; the same failure on the CURRENT sse-gen does evict it
 (with-redefs [send-effect! (fn [_ _] (throw (ex-info "Broken pipe" {})))]
   (send-replies! "cid-1" "u1" ::sse-new
                  [[:patch [:div "msg"] {}]])
   (Thread/sleep 100))
 (get-in @!connections ["cid-1" "u1"]) := nil
 (reset! !connections {})
 (destroy-user-chan! "u1")
 (reset! !user-chans {}))

(tests
 "unregister-connection! keeps channel alive when user has other connections"
 (reset! !connections {})
 (reset! !user-chans {})
 (register-connection! :comp "cid-1" ::sse-a {:user/id "user-a"})
 (register-connection! :comp "cid-2" ::sse-b {:user/id "user-a"})
 ;; disconnect from cid-1 only
 (unregister-connection! :comp "cid-1" ::sse-a {:user/id "user-a"})
 ;; still connected to cid-2, channel should survive
 (some? (get-in @!user-chans ["user-a" :chan])) := true
 ;; disconnect from cid-2
 (unregister-connection! :comp "cid-2" ::sse-b {:user/id "user-a"})
 ;; now channel should be destroyed
 (get @!user-chans "user-a") := nil
 (reset! !connections {})
 (reset! !user-chans {}))

(tests
 "ensure-user-chan! is idempotent — returns same channel on repeat calls"
 (reset! !user-chans {})
 (let [ch1 (ensure-user-chan! "user-a")
       ch2 (ensure-user-chan! "user-a")]
   (identical? ch1 ch2) := true)
 (destroy-user-chan! "user-a")
 (reset! !user-chans {}))

(tests
 "ensure-user-chan! creates different channels for different users"
 (reset! !user-chans {})
 (let [ch-a (ensure-user-chan! "user-a")
       ch-b (ensure-user-chan! "user-b")]
   (identical? ch-a ch-b) := false)
 (destroy-user-chan! "user-a")
 (destroy-user-chan! "user-b")
 (reset! !user-chans {}))

;;; Tests — per-user channel write processing

(tests
 "traced-write: a write enqueued INSIDE a trace runs under that trace (the
  captured mulog context is re-established on the consumer thread); the
  thunk's result and side effect are preserved"
 (with-redefs [log/local-context (fn [] {:mulog/root-trace :root-1 :turn "t1"})]
   ;; the write answers with the context it ran under; the thunk returns that
   ;; answer, so one assertion covers both the context and the result passthrough
   (let [write (spy/spy (fn [] (:turn (com.brunobonacci.mulog/local-context))))
         thunk (traced-write "cid-1" "u1" write)]
     (thunk) := "t1"
     (spy/call-count write) := 1))

 "traced-write: a context-less FAST write reports nothing; a SLOW one reports
  ::sse-write-slow with the elapsed write time"
 (with-redefs [log/local-context (fn [] {})
               report-slow-write! (spy/spy)]
   ((traced-write "cid-1" "u1" (fn [] :fast)))
   (spy/not-called? report-slow-write!) := true
   (with-redefs [slow-write-threshold-ms 0]
     ((traced-write "cid-1" "u1" (fn [] (Thread/sleep 5) :slow))))
   ;; [cid uid queue-wait-ms write-ms]
   (spy/calls report-slow-write!) := [["cid-1" "u1" _ _]]
   (>= (nth (spy/last-call report-slow-write!) 3) 5) := true))

;;; Tests — send-replies! via per-user channels

(tests
 "send-replies! dispatches :patch via send-effect! to single user"
 (reset! !connections {"cid-1" {"u1" ::sse1}})
 (reset! !user-chans {})
 (ensure-user-chan! "u1")
 (with-redefs [send-effect! (spy/spy)]
   (send-replies! "cid-1" "u1" ::sse1
                  [[:patch [:div "hi"] {:d*/selector "#msgs"}]])
   (Thread/sleep 100)
   ;; The 15 s heartbeat loop enqueues an inert patch-signals onto every
   ;; registered connection — drop any that lands in this window.
   (vec (remove #(= [:patch-signals {} {}] (second %)) (spy/calls send-effect!)))
   := [[::sse1 [:patch [:div "hi"] {:d*/selector "#msgs"}]]])
 (reset! !connections {})
 (destroy-user-chan! "u1")
 (reset! !user-chans {}))

(tests
 "send-replies! :patch removes stale connection on failure"
 (reset! !connections {"cid-1" {"user-bad" ::sse-bad}})
 (reset! !user-chans {})
 (ensure-user-chan! "user-bad")
 (with-redefs [send-effect! (fn [_sse-gen _effect]
                              (throw (ex-info "Broken pipe" {})))]
   (send-replies! "cid-1" "user-bad" ::sse-bad
                  [[:patch [:div "msg"] {}]])
   (Thread/sleep 100))
 (get-in @!connections ["cid-1" "user-bad"]) := nil
 (reset! !connections {})
 (destroy-user-chan! "user-bad")
 (reset! !user-chans {}))

;;; Tests — handle-ui-event

(tests
 "handle-ui-event dispatches to send-replies! for specific user"
 (reset! !connections {"cid-1" {"u1" ::sse1 "u2" ::sse2}})
 (reset! !user-chans {})
 (ensure-user-chan! "u1")
 (with-redefs [send-replies! (spy/spy)]
   (handle-ui-event ["cid-1" "u1" [[:patch [:div "x"] {}]]])
   (spy/called-once? send-replies!) := true
   (let [[comp-id user-id sse-gen responses] (first (spy/calls send-replies!))]
     comp-id := "cid-1"
     user-id := "u1"
     sse-gen := ::sse1
     responses := [[:patch [:div "x"] {}]]))
 (destroy-user-chan! "u1")
 (reset! !connections {})
 (reset! !user-chans {}))

(tests
 "handle-ui-event re-establishes the mulog context send-ui! captured, so
  send-replies! (and the write thunks it enqueues) run inside the caller's
  trace rather than context-less"
 (reset! !connections {"cid-1" {"u1" ::sse1}})
 (reset! !user-chans {})
 ;; the stub answers with the :turn of the context it was called under
 (with-redefs [send-replies! (spy/spy (fn [& _] (:turn (com.brunobonacci.mulog/local-context))))]
   (handle-ui-event ["cid-1" "u1" [[:patch [:div "x"] {}]] {:mulog/root-trace :root-9 :turn "t9"}])
   ;; a legacy 3-element message still works (no ctx → empty context)
   (handle-ui-event ["cid-1" "u1" [[:patch [:div "x"] {}]]])
   (spy/responses send-replies!) := ["t9" nil])
 (reset! !connections {}))

(tests
 "send-ui! puts the caller's mulog context on the channel alongside the message"
 (with-redefs [put! (spy/spy)
               log/local-context (fn [] {:mulog/root-trace :root-3})]
   (send-ui! "cid-1" "u1" [[:patch [:div] {}]])
   (spy/calls put!) := [[_ ["cid-1" "u1" [[:patch [:div] {}]] {:mulog/root-trace :root-3}]]]))

(tests
 "handle-ui-event is a no-op when user has no connection"
 (reset! !connections {})
 (with-redefs [send-replies! (spy/spy)]
   (handle-ui-event ["no-such-cid" "no-such-user" [[:patch [:div] {}]]])
   (spy/not-called? send-replies!) := true))

;;; Tests — ui-loop resilience

(tests
 "ui-loop continues processing after handle-ui-event throws"
 (let [test-chan (chan 10)
       fail-msg  ["cid-1" "u1" [[:patch [:div "fail"] {}]]]
       ok-msg    ["cid-2" "u2" [[:patch [:div "ok"] {}]]]
       handler   (spy/spy (fn [m] (when (= m fail-msg) (throw (ex-info "Simulated failure" {}))) m))
       t         (Thread/startVirtualThread
                  (fn []
                    (loop []
                      (when-let [msg (<!! test-chan)]
                        (try (handler msg) (catch Throwable _e nil))
                        (recur)))))]
   (put! test-chan fail-msg)
   (Thread/sleep 50)
   (put! test-chan ok-msg)
   (Thread/sleep 50)
   (spy/calls handler) := [[fail-msg] [ok-msg]]
   (.interrupt t)))
