(ns taia.ds.datastar.use-cases.process-event.controller
  "The `:controller` proc of a component's flow: the state machine.

   It reads events from the flow's in-chan (an in-port), builds the ctx the
   event transitions from (`domain.ctx`: from the client's signals, merged
   with the ctx it holds when the flow keeps one), runs the machine
   (`domain.event`) and emits {:event :initial-ctx :ctx} on `:to-view`."
  (:require
   [clojure.core.async.flow :as a.flow]
   [clojure.main :as main]
   [clojure.string :as str]
   [taia.ds.datastar.domain.ctx :as ctx]
   [taia.ds.datastar.domain.event :as event]
   [taia.ds.datastar.use-cases.process-event.common :as common]
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

;;; Tracing hooks for the state machine (at :debug)

(defn- fn-name
  "A readable name for a function object:
   my.app.components.chat.chat_actions$root_init → chat-actions/root-init"
  [f]
  (let [parts (str/split (main/demunge (-> f class .getName)) #"/")]
    (if (= 2 (count parts))
      (str (last (str/split (first parts) #"\.")) "/" (second parts))
      (str/join "/" parts))))

(tests
 "fn-name: a fn in a multi-segment ns → last-segment/fn-name"
 (fn-name clojure.string/blank?) := "string/blank?"
 (fn-name clojure.core/identity) := "core/identity"
 "fn-name: an anonymous fn still gives a non-empty string"
 (-> (fn [_])
     fn-name
     string?)
 := true
 (-> (fn [_])
     fn-name
     count
     pos?)
 := true)

(defn- trace-opts
  "The state machine's `:trace-fn` / `:action-trace-fn`, when tracing at :debug."
  []
  (when (log/trace-enabled? :debug (log/log-level))
    {:trace-fn        (fn [_invoke-id src-fn context]
                        (log/trace (keyword (str "invoke/" (fn-name src-fn))) [] (src-fn context)))
     :action-trace-fn (fn [_action-id action-fn & args]
                        (log/trace (keyword (str "action/" (fn-name action-fn))) [] (apply action-fn args)))}))

(tests
 "trace-opts: below :debug there are no hooks"
 (with-redefs [log/log-level (constantly :info)]
   (trace-opts) := nil)

 "trace-opts: at :debug both hooks are there"
 (with-redefs [log/log-level (constantly :debug)]
   (-> (trace-opts)
       keys
       set)
   := #{:trace-fn :action-trace-fn})

 "trace-opts: the invoke hook calls the src fn with the context, and returns what it returns"
 (with-redefs [log/log-level (constantly :debug)]
   (-> (trace-opts)
       :trace-fn
       (apply [:an-invoke (fn [ctx] (assoc ctx :loaded true)) {:a 1}]))
   := {:a 1 :loaded true})

 "trace-opts: the action hook applies the action fn to its args, and returns what it returns"
 (with-redefs [log/log-level (constantly :debug)]
   (-> (trace-opts)
       :action-trace-fn
       (apply [:an-action (fn [ctx params] (assoc ctx :got params)) {:a 1} {:x 2}]))
   := {:a 1 :got {:x 2}})

 "trace-opts: a src fn that throws — the exception goes through the hook unchanged"
 (with-redefs [log/log-level (constantly :debug)]
   (try ((:trace-fn (trace-opts)) :an-invoke (fn [_] (throw (ex-info "load failed" {:k 1}))) {})
        :no-throw
        (catch clojure.lang.ExceptionInfo e [(ex-message e) (ex-data e)])))
 := ["load failed" {:k 1}]

 "trace-opts: an action that throws — the exception goes through the hook unchanged"
 (with-redefs [log/log-level (constantly :debug)]
   (try ((:action-trace-fn (trace-opts)) :an-action (fn [_ _] (throw (ex-info "action failed" {}))) {} {})
        :no-throw
        (catch clojure.lang.ExceptionInfo e (ex-message e))))
 := "action failed")

;;; The proc

(defn- handle-event
  "One event through the state machine. Returns [held-ctx' out-msg]."
  [{:keys [machine-var component-name component-id held-ctx keep-ctx?]}
   {[event-name :as ev] :event :keys [signals request-ctx mulog-ctx] :as msg}]
  (log/with-context (or mulog-ctx {})
    (try
      (let [machine @machine-var        ; the var: REPL redefs are picked up
            initial (ctx/merge-held-ctx
                     held-ctx
                     (ctx/initial-ctx request-ctx signals component-id component-name (:initial machine))
                     (some? request-ctx))
            after   (log/trace-when (log/trace-enabled? :verbose (log/log-level))
                                    (keyword (str "transition-from" (:state initial) "-" (event/event-keyword event-name)))
                                    []
                                    (event/transition machine initial ev (or (trace-opts) {})))]
        [(when keep-ctx? (dissoc after :from-state))
         (common/with-reply {:event       ev
                             :initial-ctx initial
                             :ctx         (ctx/with-removal-nils initial after)
                             :mulog-ctx   mulog-ctx}
           msg)])
      (catch Throwable e
        [held-ctx (common/error e msg)]))))

;;; Tests — handle-event: one event through the state machine.
;;; It returns [the ctx to hold, the message for the view].

(tests
 (def ^:private machine-fixture
   {:id :c :initial :displayed
    :states {:displayed {:on {:INCREMENT {:actions (fn [ctx _] (update-in ctx [:memory :count] inc))}
                              :CLEAR     {:actions (fn [ctx _] (update ctx :memory dissoc :stale))}
                              :FAIL      {:actions (fn [_ _] (throw (ex-info "action failed" {})))}}}
             :running   {:on {:INCREMENT {:actions (fn [ctx _] (update-in ctx [:memory :count] inc))}}}}})
 (def ^:private p (promise))
 (defn- held [[ctx _]] ctx)
 (defn- sent [[_ msg]] msg)

 "a request event, nothing held: the ctx starts from the client's signals and
  the action runs; the new ctx is held, and the view gets the event, the ctx it
  started from and the new one"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true :held-ctx nil}
     (handle-event {:event       ["increment" {}]
                    :signals     {:count 1 :state "displayed"}
                    :request-ctx {:logged-user {:user/id "u1"}}}))
 := [{:state :displayed :memory {:count 2} :component-id "c1" :component-route :c :logged-user {:user/id "u1"}}
     {:event       ["increment" {}]
      :initial-ctx {:state "displayed" :memory {:count 1} :component-id "c1" :component-route :c
                    :logged-user {:user/id "u1"}}
      :ctx         {:state :displayed :memory {:count 2} :from-state :displayed
                    :component-id "c1" :component-route :c :logged-user {:user/id "u1"}}
      :mulog-ctx   nil}]

 "a server event (no request, no signals): it continues from the held ctx,
  not from the machine's initial state"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true
      :held-ctx    {:state :running :memory {:count 2} :component-id "c1" :component-route :c
                    :logged-user {:user/id "u1"}}}
     (handle-event {:event ["increment" {}]})
     sent
     :ctx)
 := {:state :running :memory {:count 3} :from-state :running :component-id "c1" :component-route :c
     :logged-user {:user/id "u1"}}

 "the held :state wins over a stale client echo; the client's memory is
  merged over the held memory"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true
      :held-ctx    {:state :running :memory {:count 2} :component-id "c1" :component-route :c
                    :logged-user {:user/id "u1"}}}
     (handle-event {:event       ["increment" {}]
                    :signals     {:state "displayed" :note "hi"}
                    :request-ctx {:logged-user {:user/id "u1"}}})
     sent
     :ctx)
 := {:state :running :memory {:count 3 :note "hi"} :from-state :running :component-id "c1" :component-route :c
     :logged-user {:user/id "u1"}}

 "a memory key the action removed goes to the view as nil, so the client deletes it"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true :held-ctx nil}
     (handle-event {:event ["clear" {}] :signals {:stale "x" :keep 1} :request-ctx {}})
     sent
     :ctx
     :memory)
 := {:keep 1 :stale nil}

 "the :reply and the trace ride along to the view"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true :held-ctx nil}
     (handle-event {:event ["increment" {}] :signals {:count 0} :request-ctx {} :reply p :mulog-ctx {:t 1}})
     sent
     (select-keys [:reply :mulog-ctx]))
 := {:reply p :mulog-ctx {:t 1}}

 "without :keep-ctx? nothing is held (a one-shot flow)"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? false :held-ctx nil}
     (handle-event {:event ["increment" {}] :signals {:count 0} :request-ctx {}})
     held)
 := nil

 "an action that throws: the held ctx stays as it was, and the view gets an
  error carrying the reply"
 (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true
      :held-ctx    {:state :displayed :memory {:count 2} :component-id "c1" :component-route :c
                    :logged-user {:user/id "u1"}}}
     (handle-event {:event ["fail" {}] :reply p})
     (update 1 update ::common/error ex-message))
 := [{:state :displayed :memory {:count 2} :component-id "c1" :component-route :c :logged-user {:user/id "u1"}}
     {::common/error "action failed" :mulog-ctx nil :reply p}]

 "traced (:verbose): the transition is traced, and the result is the same"
 (with-redefs [log/log-level (constantly :verbose)]
   (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true :held-ctx nil}
       (handle-event {:event ["increment" {}] :signals {:count 1 :state "displayed"} :request-ctx {}})
       sent
       :ctx)
   := {:state :displayed :memory {:count 2} :from-state :displayed
       :component-id "c1" :component-route :c :logged-user nil})

 "traced (:verbose), inside the sender's trace: the result is the same, and the trace rides along"
 (with-redefs [log/log-level (constantly :verbose)]
   (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true :held-ctx nil}
       (handle-event {:event     ["increment" {}]
                      :signals   {:count 1}
                      :mulog-ctx {:mulog/root-trace :a-root :mulog/parent-trace :a-parent}})
       sent
       (select-keys [:ctx :mulog-ctx])))
 := {:ctx       {:state :displayed :memory {:count 2} :from-state :displayed
                 :component-id "c1" :component-route :c :logged-user nil}
     :mulog-ctx {:mulog/root-trace :a-root :mulog/parent-trace :a-parent}}

 "traced (:verbose), an action that throws: the held ctx stays, the view gets the error"
 (with-redefs [log/log-level (constantly :verbose)]
   (-> {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true
        :held-ctx    {:state :displayed :memory {:count 2} :component-id "c1" :component-route :c}}
       (handle-event {:event ["fail" {}]})
       (update 1 update ::common/error ex-message)))
 := [{:state :displayed :memory {:count 2} :component-id "c1" :component-route :c}
     {::common/error "action failed" :mulog-ctx nil}]

 "traced (:debug): the machine gets the trace hooks"
 (with-redefs [log/log-level    (constantly :debug)
               event/transition (spy/spy event/transition)]
   (handle-event {:machine-var #'machine-fixture :component-name :c :component-id "c1" :keep-ctx? true :held-ctx nil}
                 {:event ["increment" {}] :signals {:count 1} :request-ctx {}})
   (-> (spy/first-call event/transition)
       last
       keys
       set)
   := #{:trace-fn :action-trace-fn}))

(defn step
  "Step fn of the `:controller` proc (see `clojure.core.async.flow/process`)."
  ([] {:params   {:machine-var    "the controller's `machine` var"
                  :component-name "the component's name (its route)"
                  :component-id   "the instance id"
                  :in-chan        "external channel events arrive on"
                  :keep-ctx?      "hold the ctx between events (connected components)"}
       :outs     {:to-view "{:event :initial-ctx :ctx}, for the view"}
       :workload :io})                  ; actions may block (an HTTP call, say)
  ([{:keys [in-chan] :as args}]
   (-> args
       (dissoc :in-chan)
       (assoc :held-ctx nil
              ::a.flow/in-ports {:in-chan in-chan})))
  ([state _transition] state)
  ([state _in msg]
   (if (nil? msg)                       ; in-chan closed
     [state nil]
     (let [[held-ctx out] (handle-event state msg)]
       [(assoc state :held-ctx held-ctx) {:to-view [out]}]))))

(def proc
  "The proc launcher. Built once (a launcher is stateless until started) from
   the `step` var, so a REPL redef reaches flows started afterwards."
  (a.flow/process #'step))

;;; Tests — step: its four arities. The transform's work is handle-event's
;;; (tested above), so here it is a spy.

(tests
 (def ^:private any-machine {:id :any})
 (def ^:private in-ch (clojure.core.async/chan))

 "describe (0): its params, its one out, an :io workload"
 (-> (step)
     (update :params (comp set keys))
     (update :outs (comp set keys)))
 := {:params   #{:machine-var :component-name :component-id :in-chan :keep-ctx?}
     :outs     #{:to-view}
     :workload :io}

 "init (1): the in-chan becomes an in-port; nothing is held yet"
 (step {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true :in-chan in-ch})
 := {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
     :held-ctx    nil
     ::a.flow/in-ports {:in-chan in-ch}}

 "transition (2): resume / pause / stop leave the state as it is"
 (-> {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
      :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
     (step ::a.flow/resume))
 := {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
     :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
 (-> {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
      :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
     (step ::a.flow/pause))
 := {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
     :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
 (-> {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
      :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
     (step ::a.flow/stop))
 := {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
     :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}

 "transform (3): hands the state and the message to handle-event, holds the
  ctx it returns, and sends its message on :to-view"
 (with-redefs [handle-event (spy/spy (constantly [{:held :ctx} {:for :view}]))]
   (-> {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
        :held-ctx    nil ::a.flow/in-ports {:in-chan in-ch}}
       (step :in-chan {:event ["go" {}]}))
   := [{:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
        :held-ctx    {:held :ctx} ::a.flow/in-ports {:in-chan in-ch}}
       {:to-view [{:for :view}]}]
   (spy/called-once-with? handle-event
                          {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
                           :held-ctx    nil ::a.flow/in-ports {:in-chan in-ch}}
                          {:event ["go" {}]})
   := true)

 "transform (3), the in-chan closed (nil): handle-event is not called, nothing
  goes out, the state is unchanged"
 (with-redefs [handle-event (spy/spy)]
   (-> {:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
        :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
       (step :in-chan nil))
   := [{:machine-var #'any-machine :component-name :c :component-id "c1" :keep-ctx? true
        :held-ctx    {:state :running} ::a.flow/in-ports {:in-chan in-ch}}
       nil]
   (spy/not-called? handle-event) := true))
