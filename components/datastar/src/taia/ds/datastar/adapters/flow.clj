(ns taia.ds.datastar.adapters.flow
  "The component flow: the component pipeline as a `clojure.core.async.flow`
   of two procs, each in its own namespace:

     in-chan ──▶ :controller ──:to-view──▶ :view ──▶ out-chan
                 (state machine)            (render)
                 flow.controller            flow.view

   This namespace owns the graph and its lifecycle (`start-component-flow!`,
   `stop-component-flow!`) and running events through it (`run-event!`,
   `process-component-event`). The messages are described in `flow.message`.

   The machine and the render fn are resolved ONCE, when the flow is built;
   the procs keep them as vars, so REPL redefs are still picked up.

   Two lifetimes:
   - a DEFAULT component gets a flow per request (`process-component-event`):
     put the event, take the render or time out, stop the flow;
   - a CONNECTED component's flow lives as long as its SSE stream, and its
     `:controller` holds the ctx between events (`:keep-ctx? true`), so any
     later event — a browser POST or a server `send-event!` — transitions from
     where the last one left off. See `flow.controller/merge-held-ctx` for how
     the held ctx and the client's signals combine."
  (:require
   [clojure.core.async :as a :refer [chan close! put! alt!! timeout]]
   [clojure.core.async.flow :as a.flow]
   [taia.ds.datastar.adapters.flow.controller :as controller]
   [taia.ds.datastar.adapters.flow.message :as message]
   [taia.ds.datastar.adapters.flow.view :as view]
   [taia.ds.datastar.adapters.handlers.discovery :as discovery]
   [taia.ds.datastar.adapters.handlers.pipeline :as pipeline]
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]])
  (:import
   (java.util.concurrent ExecutorService Executors)))

;; Proc loops run on virtual threads: a connected flow parks two loops for the
;; whole life of its stream, and a per-request flow is created per event.
(defonce ^:private ^ExecutorService executor (Executors/newVirtualThreadPerTaskExecutor))

;;; Lifecycle

(defn start-component-flow!
  "Resolves the component's machine and render fn (discovery runs here, on the
   caller's thread, so an unknown component throws before any flow exists),
   then builds, starts and resumes its flow.
   Returns {:flow :in-chan :out-chan :component-name :component-id}."
  [{:keys [component-name component-id controller-name keep-ctx?]}]
  (let [{:keys [machine-var component-fn-var]}
        (pipeline/assoc-component-fn-and-machine {:component-name  component-name
                                                  :controller-name controller-name})
        in-chan  (chan 16)
        out-chan (chan 16)
        g        (a.flow/create-flow
                  {:procs      {:controller {:proc controller/proc
                                             :args {:machine-var     machine-var
                                                    :component-name  component-name
                                                    :component-id    component-id
                                                    :controller-name controller-name
                                                    :in-chan         in-chan
                                                    :keep-ctx?       (boolean keep-ctx?)}}
                                :view       {:proc view/proc
                                             :args {:component-fn-var component-fn-var
                                                    :out-chan         out-chan}}}
                   :conns      [[[:controller :to-view] [:view :from-controller]]]
                   :mixed-exec executor
                   :io-exec    executor})]
    (a.flow/start g)
    (a.flow/resume g)
    {:flow           g
     :in-chan        in-chan
     :out-chan       out-chan
     :component-name component-name
     :component-id   component-id}))

(defn stop-component-flow!
  "Stops the flow's procs and closes its in/out channels. Idempotent."
  [{:keys [flow in-chan out-chan]}]
  (a.flow/stop flow)
  (close! in-chan)
  (close! out-chan)
  nil)

(defn event-msg
  "The in-chan message for an event: `assoc-event` extracts
   [event-name params] from the request (nil for a server event → no
   params); the caller's trace rides along."
  [req component-id event-name]
  {:event     (-> (pipeline/assoc-event {:request req :component-id component-id} event-name)
                  :event
                  (update 1 #(or % {})))
   :request   req
   :mulog-ctx (log/local-context)})

(def event-timeout-ms
  "How long a caller waits for an event's render before giving up."
  30000)

(def error-of
  "The exception a render from a flow's out-chan carries, or nil."
  message/error-of)

(defn await-render
  "Takes one render from `ch` (a promise is deref'd), or throws on timeout.
   An error message (`message/error`) is rethrown as the exception it carries."
  [ch-or-promise component-name event-name]
  (let [result (if (instance? clojure.lang.IPending ch-or-promise)
                 (deref ch-or-promise event-timeout-ms ::timeout)
                 (alt!! ch-or-promise ([r] r)
                        (timeout event-timeout-ms) ::timeout))]
    (cond
      (= ::timeout result)
      (throw (ex-info (str "Timed out after " event-timeout-ms " ms waiting for "
                           component-name " to handle " event-name)
                      {::timeout true :component-name component-name :event-name event-name}))

      (nil? result)
      (throw (ex-info (str "Flow closed before " component-name " handled " event-name)
                      {::closed true :component-name component-name :event-name event-name}))

      (message/error-of result) (throw (message/error-of result))
      :else            result)))

(defn run-event!
  "Puts one event on a flow you started (`start-component-flow!`) and waits
   for its render on the flow's out-chan. `req` is the request the event came
   with, or nil for a server event (no client signals). Returns the result map
   (:ctx, :html, …); throws like `await-render`. Not for a flow whose out-chan
   someone else drains (a connection's flow — use `send-event!` there)."
  [{:keys [in-chan out-chan component-name component-id]} req event-name]
  (put! in-chan (event-msg req component-id event-name))
  (await-render out-chan component-name event-name))

(defn process-component-event
  "Runs one event through a per-request flow: discovery (at construction) →
   :controller (initial ctx, state machine, removal nils) → :view (render).
   Returns the result map with :html (plain hiccup or an effects list) and
   :ctx. Throws what a step threw, or an ex-info with ::timeout."
  [req component-name component-id event-name controller-name]
  (log/trace-when (log/trace-enabled? :verbose (log/log-level))
                  (keyword (str "process-component-event/" event-name))
                  [:component-name component-name
                   :component-id component-id
                   :event-name event-name
                   :controller-name controller-name]
                  (let [cf (start-component-flow! {:component-name  component-name
                                                   :component-id    component-id
                                                   :controller-name controller-name})]
                    (try
                      (run-event! cf req event-name)
                      (finally
                        (stop-component-flow! cf))))))

(tests
 "process-component-event: full happy-path pipeline — discovery → event →
  initial-ctx (with :app-config forwarded) → SM transition → render. The
  component fn sees the post-transition ctx and returns hiccup that closes
  over both event-params and :app-config."
 (def ^:private pce-machine
   {:id :pce :initial :idle
    :states {:idle {:on {:DO {:target  :done
                              :actions (fn [ctx params]
                                         (assoc-in ctx [:memory :last-event] params))}}}
             :done {}}})

 (def ^:private pce-component-fn
   (fn [ctx]
     [:div
      {:state    (:state ctx)
       :grafana  (get-in ctx [:app-config :grafana :base-url])
       :last     (get-in ctx [:memory :last-event])}]))

 (with-redefs [discovery/discover-component
               (fn ([_]   {:machine #'pce-machine :component-fn #'pce-component-fn})
                 ([_ _] {:machine #'pce-machine :component-fn #'pce-component-fn}))]
   (let [req    {:body-params {}
                 :params      {:logged-user {:user/id "u-1"}
                               :note        "hi"}
                 :app-config  {:grafana {:base-url "http://localhost:3000"}}}
         result (process-component-event req :pce "1" "DO" nil)]
     (:html result)))
 := [:div {:state :done
           :grafana "http://localhost:3000"
           :last {:note "hi"}}]

 "process-component-event: a memory key dissoc'd by an action is re-emitted as
  nil in the rendered ctx, so the data-signals round-trip deletes it client-side"
 (def ^:private pce-dissoc-machine
   {:id :pce-dissoc :initial :idle
    :states {:idle {:on {:CLEAR {:target  :done
                                 :actions (fn [ctx _params]
                                            (update ctx :memory dissoc :stale))}}}
             :done {}}})

 (def ^:private pce-memory-component-fn
   (fn [ctx] [:div {:memory (:memory ctx)}]))

 (with-redefs [discovery/discover-component
               (fn ([_]   {:machine #'pce-dissoc-machine :component-fn #'pce-memory-component-fn})
                 ([_ _] {:machine #'pce-dissoc-machine :component-fn #'pce-memory-component-fn}))]
   (-> (process-component-event {:body-params {:1 {:stale "x" :keep 1 :state "idle"}}
                                 :params      {}}
                                :pce-dissoc "1" "CLEAR" nil)
       :html
       (get-in [1 :memory])))
 := {:keep 1 :stale nil}

 "process-component-event: RENDERER.* internal event short-circuits transition,
  component fn still rendered against initial-ctx"
 (with-redefs [discovery/discover-component
               (fn ([_]   {:machine #'pce-machine :component-fn #'pce-component-fn})
                 ([_ _] {:machine #'pce-machine :component-fn #'pce-component-fn}))]
   (-> (process-component-event {:body-params {} :params {}} :pce "1" "RENDERER.LOADED" nil)
       :html
       (get-in [1 :state])))
 := :idle)

(tests
 "process-component-event: an action that throws surfaces as that exception
  on the caller's thread (the default handler turns it into its 400)"
 (def ^:private boom-machine
   {:id :boom :initial :idle
    :states {:idle {:on {:GO {:actions (fn [_ _] (throw (ex-info "boom" {::k 1})))}}}}})
 (def ^:private boom-view (fn [_] [:div]))
 (with-redefs [discovery/discover-component
               (fn ([_] {:machine #'boom-machine :component-fn #'boom-view})
                 ([_ _] {:machine #'boom-machine :component-fn #'boom-view}))]
   (try (process-component-event {:params {}} :boom "1" "GO" nil)
        :no-throw
        (catch clojure.lang.ExceptionInfo e [(ex-message e) (::k (ex-data e))])))
 := ["boom" 1]

 "process-component-event: a render that throws surfaces too"
 (def ^:private bad-view (fn [_] (throw (ex-info "render failed" {}))))
 (with-redefs [discovery/discover-component
               (fn ([_] {:machine #'pce-machine :component-fn #'bad-view})
                 ([_ _] {:machine #'pce-machine :component-fn #'bad-view}))]
   (try (process-component-event {:params {}} :pce "1" "DO" nil)
        :no-throw
        (catch clojure.lang.ExceptionInfo e (ex-message e))))
 := "render failed"

 "process-component-event: an action slower than event-timeout-ms → ::timeout"
 (def ^:private slow-machine
   {:id :slow :initial :idle
    :states {:idle {:on {:GO {:actions (fn [ctx _] (Thread/sleep 300) ctx)}}}}})
 (with-redefs [discovery/discover-component
               (fn ([_] {:machine #'slow-machine :component-fn #'boom-view})
                 ([_ _] {:machine #'slow-machine :component-fn #'boom-view}))
               event-timeout-ms 50]
   (try (process-component-event {:params {}} :slow "1" "GO" nil)
        :no-throw
        (catch clojure.lang.ExceptionInfo e (::timeout (ex-data e)))))
 := true)

(tests
 "a connected flow (keep-ctx? true) holds the ctx: INIT, then two server
  events with NO request — each transitions from where the last one left off"
 (def ^:private tick-machine
   {:id :tick :initial :idle
    :states {:idle    {:on {:INIT  {:actions (fn [ctx _] (assoc ctx :remaining 2))}
                            :START {:target :running}}}
             :running {:on {:TICK [{:cond    (fn [ctx _] (<= (:remaining ctx) 1))
                                    :target  :done
                                    :actions (fn [ctx _] (assoc ctx :remaining 0))}
                                   {:actions (fn [ctx _] (update ctx :remaining dec))}]}}
             :done    {}}})
 (def ^:private tick-view (fn [ctx] [:p (:state ctx) (:remaining ctx) (:memory ctx)]))
 (def tick-cf
   (with-redefs [discovery/discover-component
                 (fn ([_] {:machine #'tick-machine :component-fn #'tick-view})
                   ([_ _] {:machine #'tick-machine :component-fn #'tick-view}))]
     (start-component-flow! {:component-name :tick :component-id "t1" :keep-ctx? true})))

 (defn- send! [msg] (put! (:in-chan tick-cf) msg) (:html (await-render (:out-chan tick-cf) :tick "?")))

 (send! (event-msg {:params {:logged-user {:user/id "u1"}}} "t1" "INIT")) := [:p :idle 2 {}]
 (send! {:event ["START" {}]})  := [:p :running 2 {}]
 (send! {:event ["TICK" {}]})   := [:p :running 1 {}]

 "a browser event merges its signals into the held memory; the held state wins
  over the client's stale :state echo"
 (send! (event-msg {:params {} :body-params {:t1 {:state "idle" :note "hi"}}} "t1" "TICK"))
 := [:p :done 0 {:note "hi"}]

 "a :reply promise rides through both procs onto the render"
 (let [p (promise)]
   (put! (:in-chan tick-cf) {:event ["RENDERER.PING" {}] :reply p})
   (identical? p (:reply (await-render (:out-chan tick-cf) :tick "RENDERER.PING")))) := true

 (stop-component-flow! tick-cf)
 "after stop, the out-chan is closed"
 (a/<!! (:out-chan tick-cf)) := nil)
