(ns taia.ds.datastar.use-cases.process-event
  "The use case: process a component's event. It runs as a
   `clojure.core.async.flow` of two procs, each in its own namespace:

     in-chan ──▶ :controller ──:to-view──▶ :view ──▶ out-chan
                 (state machine)            (render)
                 process-event.controller   process-event.view

   The messages are described in `process-event.common`. Everything here is
   plain data in and out: the caller resolves the component (its machine and
   render fn, as vars) and turns a request into a message (adapters); the
   rules about the ctx and the transition are the domain's.

   Two lifetimes:
   - a DEFAULT component's event gets a flow of its own (`process-event`):
     put the event, take the render or time out, stop the flow;
   - a CONNECTED component's flow lives as long as its SSE stream
     (`start-flow!`), so a later event — a browser POST or a server
     `send-event!` — goes in through the same in-chan.
   Either way nothing is kept between events: each starts from the client's
   signals (a click) or from the machine's initial state (a server event,
   which brings what it needs as its params)."
  (:require
   [clojure.core.async :as a :refer [chan close! put! alt!! timeout]]
   [clojure.core.async.flow :as a.flow]
   [taia.ds.datastar.use-cases.process-event.common :as common]
   [taia.ds.datastar.use-cases.process-event.controller :as controller]
   [taia.ds.datastar.use-cases.process-event.view :as view]
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy])
  (:import
   (java.util.concurrent ExecutorService Executors)))

;; Proc loops run on virtual threads: a connected flow parks two loops for the
;; whole life of its stream, and a one-shot event builds a flow of its own.
(defonce ^:private ^ExecutorService executor (Executors/newVirtualThreadPerTaskExecutor))

;;; Lifecycle

(defn start-flow!
  "Builds, starts and resumes a component's flow. `component` is the resolved
   component — {:machine-var :component-fn-var} — plus its :component-name and
   :component-id. Returns {:flow :in-chan :out-chan :component-name :component-id}."
  [{:keys [machine-var component-fn-var component-name component-id]}]
  (let [in-chan  (chan 16)
        out-chan (chan 16)
        g        (a.flow/create-flow
                  {:procs      {:controller {:proc controller/proc
                                             :args {:machine-var    machine-var
                                                    :component-name component-name
                                                    :component-id   component-id
                                                    :in-chan        in-chan}}
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

(defn stop-flow!
  "Stops the flow's procs and closes its in/out channels. Idempotent."
  [{:keys [flow in-chan out-chan]}]
  (a.flow/stop flow)
  (close! in-chan)
  (close! out-chan)
  nil)

(tests
 (def ^:private stop-in-ch (chan 1))
 (def ^:private stop-out-ch (chan 1))

 "stop-flow!: stops the flow's procs and closes its in- and out-chan (a put
  on a closed channel returns false)"
 (with-redefs [a.flow/stop (spy/spy)]
   (stop-flow! {:flow :a-flow :in-chan stop-in-ch :out-chan stop-out-ch}) := nil
   (spy/called-once-with? a.flow/stop :a-flow) := true
   (put! stop-in-ch :x) := false
   (put! stop-out-ch :x) := false))

;;; Events

(def event-timeout-ms
  "How long a caller waits for an event's render before giving up."
  30000)

(defn await-render
  "Takes one render from `ch` (a promise is deref'd), or throws on timeout.
   An error message is rethrown as the exception it carries."
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

      (common/error-of result) (throw (common/error-of result))
      :else                    result)))

(tests
 (def ^:private boom (ex-info "boom" {::k 1}))

 "await-render: the render taken from a channel"
 (await-render (a/to-chan! [{:html [:p]}]) :c "go") := {:html [:p]}

 "await-render: a promise is deref'd"
 (await-render (doto (promise) (deliver {:html [:p]})) :c "go") := {:html [:p]}

 "await-render: an error message → the exception it carries is rethrown"
 (try (await-render (a/to-chan! [{::common/error boom :mulog-ctx nil}]) :c "go")
      :no-throw
      (catch clojure.lang.ExceptionInfo e (identical? boom e)))
 := true

 "await-render: the channel closes before a render → ::closed"
 (try (await-render (doto (chan) close!) :c "go")
      :no-throw
      (catch clojure.lang.ExceptionInfo e (ex-data e)))
 := {::closed true :component-name :c :event-name "go"}

 "await-render: nothing within event-timeout-ms → ::timeout"
 (with-redefs [event-timeout-ms 50]
   (try (await-render (chan) :c "go")
        :no-throw
        (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)])))
 := ["Timed out after 50 ms waiting for :c to handle go"
     {::timeout true :component-name :c :event-name "go"}])

(defn run-event!
  "Puts `msg` on a flow you started (`start-flow!`) and waits for its render
   on the flow's out-chan. Returns {:event :initial-ctx :ctx :html …}; throws
   like `await-render`. Not for a flow whose out-chan someone else drains (a
   connection's flow — send it events with a :reply instead)."
  [{:keys [in-chan out-chan component-name]} msg]
  (put! in-chan msg)
  (await-render out-chan component-name (first (:event msg))))

;;; Tests — run-event!: waiting for the render is await-render's work (tested
;;; above), so here it is a spy.

(tests
 (def ^:private run-in-ch (chan 1))
 (def ^:private run-out-ch (chan 1))

 "run-event!: puts the message on the in-chan, then awaits the render of its
  event on the out-chan"
 (with-redefs [await-render (spy/spy (constantly {:html [:p]}))]
   (run-event! {:in-chan run-in-ch :out-chan run-out-ch :component-name :c} {:event ["go" {}]})
   := {:html [:p]}
   (spy/called-once-with? await-render run-out-ch :c "go") := true
   (a/poll! run-in-ch) := {:event ["go" {}]}))

(defn process-event
  "Processes one event of a one-shot component in a flow of its own: built
   from the resolved `component` (see `start-flow!`), given `msg`, stopped once
   it has answered. Returns {:event :initial-ctx :ctx :html …}. Throws what a
   step threw, or an ex-info with ::timeout."
  [{:keys [component-name component-id] :as component} msg]
  (log/trace-when (log/trace-enabled? :verbose (log/log-level))
                  (keyword (str "process-event/" (first (:event msg))))
                  [:component-name component-name :component-id component-id]
                  (let [cf (start-flow! component)]
                    (try
                      (run-event! cf msg)
                      (finally
                        (stop-flow! cf))))))

;;; Tests — process-event: the flow's lifecycle and running the event are
;;; start-flow! / run-event! / stop-flow!'s work, so here they are spies.

(tests
 "process-event: starts a flow for the component, runs the event through it,
  stops it, and returns the render"
 (with-redefs [start-flow! (spy/spy (constantly :a-flow))
               run-event!  (spy/spy (constantly {:html [:p]}))
               stop-flow!  (spy/spy)]
   (process-event {:component-name :c :component-id "c1"} {:event ["go" {}]}) := {:html [:p]}
   (spy/called-once-with? start-flow! {:component-name :c :component-id "c1"}) := true
   (spy/called-once-with? run-event! :a-flow {:event ["go" {}]}) := true
   (spy/called-once-with? stop-flow! :a-flow) := true)

 "process-event: run-event! throws → the flow is still stopped, and the
  exception reaches the caller"
 (with-redefs [start-flow! (spy/spy (constantly :a-flow))
               run-event!  (spy/spy (fn [_ _] (throw (ex-info "boom" {}))))
               stop-flow!  (spy/spy)]
   (try (process-event {:component-name :c :component-id "c1"} {:event ["go" {}]})
        :no-throw
        (catch clojure.lang.ExceptionInfo e (ex-message e)))
   := "boom"
   (spy/called-once-with? stop-flow! :a-flow) := true)

 "process-event: traced (:verbose) — the same calls and the same render"
 (with-redefs [log/log-level (constantly :verbose)
               start-flow!   (spy/spy (constantly :a-flow))
               run-event!    (spy/spy (constantly {:html [:p]}))
               stop-flow!    (spy/spy)]
   (process-event {:component-name :c :component-id "c1"} {:event ["go" {}]}) := {:html [:p]}
   (spy/called-once-with? start-flow! {:component-name :c :component-id "c1"}) := true
   (spy/called-once-with? run-event! :a-flow {:event ["go" {}]}) := true
   (spy/called-once-with? stop-flow! :a-flow) := true)

 "process-event: traced (:verbose), run-event! throws → the flow is still stopped, the exception reaches the caller"
 (with-redefs [log/log-level (constantly :verbose)
               start-flow!   (spy/spy (constantly :a-flow))
               run-event!    (spy/spy (fn [_ _] (throw (ex-info "boom" {}))))
               stop-flow!    (spy/spy)]
   (try (process-event {:component-name :c :component-id "c1"} {:event ["go" {}]})
        :no-throw
        (catch clojure.lang.ExceptionInfo e (ex-message e)))
   := "boom"
   (spy/called-once-with? stop-flow! :a-flow) := true))

;;; Tests — END TO END: real flows, fixture components. Every piece is tested
;;; on its own (above, and in process-event.controller / .view / domain);
;;; these check only the wiring of the procs: an event goes in and its render
;;; comes out, an error crosses them, a connected flow holds its ctx.

(tests
 (def ^:private counter-machine
   {:id :counter :initial :displayed
    :states {:displayed {:on {:INCREMENT {:actions (fn [ctx _] (update-in ctx [:memory :count] inc))}}}}})
 (def ^:private counter-view (fn [ctx] [:p (:state ctx) (:memory ctx)]))
 (def ^:private reply (promise))

 "END TO END, one-shot: signals → ctx → transition → render, in a flow of its
  own; the :reply rides through both procs"
 (-> {:machine-var #'counter-machine :component-fn-var #'counter-view :component-name :counter :component-id "c1"}
     (process-event {:event       ["increment" {}]
                     :signals     {:count 1 :state "displayed"}
                     :request-ctx {:logged-user {:user/id "u1"}}
                     :reply       reply}))
 := {:event       ["increment" {}]
     :initial-ctx {:state "displayed" :memory {:count 1} :component-id "c1" :component-route :counter
                   :logged-user {:user/id "u1"}}
     :ctx         {:state :displayed :memory {:count 2} :from-state :displayed :component-id "c1"
                   :component-route :counter :logged-user {:user/id "u1"}}
     :mulog-ctx   nil
     :reply       reply
     :html        [:p :displayed {:count 2}]}

 "END TO END, one-shot: an action that throws surfaces as that exception on
  the caller's thread"
 (def ^:private boom-machine
   {:id :boom :initial :idle
    :states {:idle {:on {:GO {:actions (fn [_ _] (throw (ex-info "boom" {::k 1})))}}}}})
 (try (process-event {:machine-var #'boom-machine :component-fn-var #'counter-view
                      :component-name :counter :component-id "c1"}
                     {:event ["GO" {}]})
      :no-throw
      (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)]))
 := ["boom" {::k 1}])

(tests
 "END TO END, connected (a sequence: one flow, several events through the same
  in-chan): a click starts from its signals, a server event from the machine's
  initial state and its params — nothing is carried from one event to the next"
 (def ^:private tick-machine
   {:id :tick :initial :idle
    :on {:TICK {:target  :running
                :actions (fn [ctx {:keys [remaining]}] (assoc ctx :remaining remaining))}}
    :states {:idle    {:on {:INIT  {:actions (fn [ctx _] (assoc ctx :remaining 5))}
                            :START {:target :running :actions (fn [ctx _] (assoc ctx :remaining 5))}}}
             :running {}}})
 (def ^:private tick-view (fn [ctx] [:p (:state ctx) (:remaining ctx) (:memory ctx)]))
 (def ^:private connected (start-flow! {:machine-var #'tick-machine :component-fn-var #'tick-view
                                        :component-name :tick :component-id "t1"}))

 (-> connected
     (run-event! {:event ["INIT" {}] :request-ctx {:logged-user {:user/id "u1"}}})
     :html)
 := [:p :idle 5 {}]

 (-> connected
     (run-event! {:event ["START" {}] :signals {:note "hi"} :request-ctx {}})
     :html)
 := [:p :running 5 {:note "hi"}]

 (-> connected
     (run-event! (common/server-event "tick" {:remaining 4}))
     :html)
 := [:p :running 4 {}]

 (stop-flow! connected))
