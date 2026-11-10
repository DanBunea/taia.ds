(ns taia.ds.state-machines.hierarchical-state-machine
  (:require
   [spy.core :as spy]
   [hyperfiddle.rcf :refer [tests]]))

(defn state-key-from-opts [opts]
  (or (:state-key opts) :_state))

(defn- state-or-state-as-ls [state]
  (if (= 1 (count state))
    (first state)
    state))

(defn- new-state [old-state-path new-state]
  (if (coll? new-state)
    (state-or-state-as-ls new-state)
    (let [state (-> old-state-path
                    drop-last
                    (concat [new-state])
                    vec)]
      (state-or-state-as-ls state))))

(defn- state-path [from-state opts]
  (let [state-key (state-key-from-opts opts)]
    (if (coll? (state-key from-state))
      (state-key from-state)
      [(state-key from-state)])))

(defn- event-on-path [event-name]
  [:on event-name])

(defn- event-states-on-path [from-state event-name opts]
  (concat
   [:states]
   (->> (state-path from-state opts)
        (interpose :states)
        vec)
   [:on
    event-name]))

(defn- warn [text]
  (println ::warn text))

(defn- warn-if-nil [text value]
  (when (nil? value) (warn text))
  value)

(defn- has-multiple-options? [v]
  (and (vector? v)
       (every? coll? v)))

(defn- find-start-point [machine from-state event-name event-params opts]
  (let [state-key (state-key-from-opts opts)
        on-path (event-on-path event-name)
        states-path (event-states-on-path from-state event-name opts)
        context (dissoc from-state state-key)

        result (or (get-in machine on-path)
                   (get-in machine states-path))]

    (if (has-multiple-options? result)
      (->> result
           (filter (fn [b] (let [check-cond (:cond b)]
                             (or (nil? check-cond)
                                 (check-cond context event-params)))))
           first
           (warn-if-nil
            (str "No matching condition for the event " event-name ". Event params " (prn-str event-params) ". Machine: " (prn-str machine))))
      (warn-if-nil
       (str "Event " event-name " not found!. Event params " (prn-str event-params) ". Paths: " (prn-str on-path)  " or " (prn-str states-path) " don't exist in machine: " (:id machine))
       result))))

(defn- execute-invoke [invoke-config context opts]
  (let [trace-fn (:trace-fn opts)
        invoke-id (or (:id invoke-config) :unknown)]
    (try
      (let [result (if trace-fn
                     (trace-fn invoke-id (:src invoke-config) context)
                     ((:src invoke-config) context))]
        [:on-done result])
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
        [:on-error (ex-data e)]))))

(defn- handle-invoke-result [machine from-state [event result] opts]
  (let [state-key (state-key-from-opts opts)
        invoke-config (:invoke from-state)
        raw-event-config (get invoke-config event)
        context (dissoc from-state state-key :invoke)
        ;; Resolve conditional on-done/on-error — same pattern as event conds
        ;; in find-start-point. Cond fn signature: (fn [context result]).
        event-config (if (has-multiple-options? raw-event-config)
                       (->> raw-event-config
                            (filter (fn [opt]
                                      (let [check-cond (:cond opt)]
                                        (or (nil? check-cond)
                                            (check-cond context result)))))
                            first)
                       raw-event-config)
        target (cond
                 (vector? event-config) event-config
                 (map? event-config)    (:target event-config)
                 (keyword? event-config) event-config)
        action (when (map? event-config) (:actions event-config))
        action-trace-fn (:action-trace-fn opts)
        old-state-path (state-path from-state opts)
        new-context (cond
                      action (if action-trace-fn
                               (action-trace-fn event action context result)
                               (action context result))
                      :else context)]
    (if target
      (let [result-state (merge
                          {state-key (new-state old-state-path target)}
                          new-context)
            ;; Check if the target state has an invoke config
            target-state (get-in machine (concat [:states]
                                                 (interpose :states (state-path result-state opts))))
            target-invoke-config (:invoke target-state)]
        (if target-invoke-config
          ;; Execute invoke immediately after transition
          (let [from-state-with-invoke (assoc result-state
                                              :invoke
                                              target-invoke-config)
                [invoke-result invoke-result-data] (execute-invoke target-invoke-config (dissoc result-state state-key) opts)]
            (handle-invoke-result machine from-state-with-invoke [invoke-result invoke-result-data] opts))
          ;; Return normal transition result
          result-state))
      (merge
       {state-key (state-key from-state)}
       new-context))))

(defn transition
  ([machine from-state [event-name event-params]]
   (transition machine from-state [event-name event-params] {:state-fn :_state}))
  ([machine from-state [event-name event-params] opts]
   (let [state-key (state-key-from-opts opts)
         context (dissoc from-state state-key :invoke)
         old-state-path (state-path from-state opts)
         start-point (find-start-point machine from-state event-name event-params opts)
         action (:actions start-point)

         result (cond
                  (nil? start-point)
                  from-state

                  (and (map? start-point) (:target start-point))
                  {state-key (new-state old-state-path (:target start-point))}

                  (and (map? start-point) (nil? (:target start-point)))
                  {state-key (new-state old-state-path (state-key from-state))}

                  :else
                  {state-key (new-state old-state-path start-point)})

         action-trace-fn (:action-trace-fn opts)
         result-with-context (if action
                               (let [action-result (if action-trace-fn
                                                     (action-trace-fn event-name action context event-params)
                                                     (action context event-params))]
                                 (merge result action-result))
                               (merge result context))

        ;; Check if we just transitioned to a state with invoke
         target-state (get-in machine (concat [:states]
                                              (interpose :states (state-path result-with-context opts))))
         invoke-config (:invoke target-state)]

     (if invoke-config
      ;; Execute invoke immediately after transition
       (let [from-state-with-invoke (assoc result-with-context
                                           :invoke
                                           invoke-config)
             [invoke-result result] (execute-invoke invoke-config (dissoc result-with-context state-key) opts)]
         (handle-invoke-result machine from-state-with-invoke [invoke-result result] opts))

      ;; Return normal transition result
       result-with-context))))

(tests
 "transition should transition from a state to another, with no context"
 (def machine {:on {:GE1 :B
                    :GE2 {:target :C}
                    :GE3 {}}
               :states {:A {:on {:E1 :B}}
                        :B {:on {:E2 {:target :C}
                                 :E3 {}}}
                        :C {:states {:C1 {:on {:EC1 :C2}}
                                     :C2 {:on {:EC2 [:B]
                                               :EC3 {}
                                               :EC4 {:target :C3}
                                               :EC5 {:target [:C :C1]}
                                               :EC6 [{:target :C3 :cond (fn [_ _] false)}
                                                     {:target [:B] :cond (fn [_ _] true)}]
                                               :EC7 [{:target :C3 :cond (fn [_ _] true)}
                                                     {:target [:B] :cond (fn [_ _] false)}]
                                               :EC8 [{:target :C3 :cond (fn [_ _] false)}
                                                     {:target [:B]}]}}
                                     :C3 {}}}}})
 (transition
  machine
  {:_state :A}
  [:E1 {}]) := {:_state :B}

 (transition
  machine
  {:aa :A}
  [:E1 {}]
  {:state-key :aa}) := {:aa :B}

 (transition
  machine
  {:_state :B}
  [:E2 {}]) := {:_state :C}

 (transition
  machine
  {:_state :B}
  [:E3 {}]) := {:_state :B}

 (transition
  machine
  {:_state :A}
  [:GE1 {}]) := {:_state :B}

 (transition
  machine
  {:_state :B}
  [:GE2 {}]) := {:_state :C}

 (transition
  machine
  {:_state :B}
  [:GE3 {}]) := {:_state :B}

 (transition
  machine
  {:_state [:C :C1]}
  [:EC1 {}]) := {:_state [:C :C2]}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC2 {}]) := {:_state :B}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC3 {}]) := {:_state [:C :C2]}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC4 {}]) := {:_state [:C :C3]}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC5 {}])
 := {:_state [:C :C1]}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC6 {}])
 := {:_state :B}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC7 {}])
 := {:_state [:C :C3]}

 (transition
  machine
  {:_state [:C :C2]}
  [:EC8 {}])
 := {:_state :B}

 (transition
  machine
  {:x [:C :C2]}
  [:EC8 {}]
  {:state-key :x})
 := {:x :B})

(tests
 "transition should transition from a state to another, preserving the context sent"
 (def machine {:on {:GE1 :B
                    :GE2 {:target :C}
                    :GE3 {}}
               :states {:A {:on {:E1 :B}}
                        :B {:on {:E2 {:target :C}
                                 :E3 {}}}
                        :C {:states {:C1 {:on {:EC1 :C2}}
                                     :C2 {:on {:EC2 [:B]
                                               :EC3 {}
                                               :EC4 {:target :C3}
                                               :EC5 {:target [:C :C1]}
                                               :EC6 [{:target :C3 :cond (fn [_ _] false)}
                                                     {:target [:B] :cond (fn [_ _] true)}]
                                               :EC7 [{:target :C3 :cond (fn [_ _] true)}
                                                     {:target [:B] :cond (fn [_ _] false)}]}}
                                     :C3 {}}}}})
 (transition
  machine
  {:_state :A :something "here"}
  [:E1 {}])
 := {:_state :B  :something "here"}

 (transition
  machine
  {:_state :B  :something "here"}
  [:E2 {}])
 := {:_state :C  :something "here"}

 (transition
  machine
  {:_state :B  :something "here"}
  [:E3 {}])
 := {:_state :B  :something "here"}

 (transition
  machine
  {:_state :A  :something "here"}
  [:GE1 {}])
 := {:_state :B  :something "here"}

 (transition
  machine
  {:_state :B  :something "here"}
  [:GE2 {}])
 := {:_state :C  :something "here"}

 (transition
  machine
  {:_state :B  :something "here"}
  [:GE3 {}])
 := {:_state :B  :something "here"}

 (transition
  machine
  {:_state [:C :C1]  :something "here"}
  [:EC1 {}])
 := {:_state [:C :C2]  :something "here"}

 (transition
  machine
  {:_state [:C :C2] :something "here"}
  [:EC2 {}])
 := {:_state :B :something "here"}

 (transition
  machine
  {:_state [:C :C2]  :something "here"}
  [:EC3 {}])
 := {:_state [:C :C2]  :something "here"}

 (transition
  machine
  {:_state [:C :C2]  :something "here"}
  [:EC4 {}])
 := {:_state [:C :C3]  :something "here"}

 (transition
  machine
  {:_state [:C :C2] :something "here"}
  [:EC5 {}])
 := {:_state [:C :C1] :something "here"}

 (transition
  machine
  {:_state [:C :C2] :something "here"}
  [:EC6 {}])
 := {:_state :B :something "here"}

 (transition
  machine
  {:_state [:C :C2] :something "here"}
  [:EC7 {}])
 := {:_state [:C :C3] :something "here"})

(tests "transition should transition from a state to another running the action"
       (def machine {:on {:GE1 {:actions (fn [ctx _] (assoc ctx :count 3))  :target :C}}
                     :states {:A {:on {:E1 {:actions (fn [ctx _] (assoc ctx :count 2)) :target :B}}}
                              :B {:on {}}
                              :C {:states {:C1 {:on {:EC2 {:actions (fn [ctx _] (assoc ctx :count 1)) :target :C2}
                                                     :EC3 [{:actions (fn [ctx _] (assoc ctx :count 77)) :target :C2 :cond (fn [_ _] false)}
                                                           {:actions (fn [ctx _] (assoc ctx :count 4)) :target [:B] :cond (fn [_ _] true)}]
                                                     :EC4 [{:actions (fn [ctx _] (assoc ctx :count 5)) :target :C2 :cond (fn [_ _] true)}
                                                           {:actions (fn [ctx _] (assoc ctx :count 99)) :target [:B] :cond (fn [_ _] false)}]}}
                                           :C2 {}}}}})
       (transition
        machine
        {:_state :A}
        [:E1 {}])
       {:_state :B :count 2}

       (transition
        machine
        {:_state :B}
        [:GE1 {}])
       := {:_state :C :count 3}
       (transition
        machine
        {:_state [:C :C1]}
        [:EC2 {}])
       := {:_state [:C :C2] :count 1}

       (transition
        machine
        {:_state [:C :C1]}
        [:EC3 {}])
       := {:_state :B :count 4}

       (transition
        machine
        {:_state [:C :C1]}
        [:EC4 {}])
       := {:_state [:C :C2] :count 5})

(tests "transition should transition from a state to another running the action adding to the context received through the transition fn"
       (def machine {:on {:GE1 {:actions (fn [ctx _] (assoc ctx :count 3))  :target :C}}
                     :states {:A {:on {:E1 {:actions (fn [ctx _] (assoc ctx :count 2)) :target :B}}}
                              :B {:on {}}
                              :C {:states {:C1 {:on {:EC2 {:actions (fn [ctx _] (assoc ctx :count 1)) :target :C2}
                                                     :EC3 [{:actions (fn [ctx _] (assoc ctx :count 77)) :target :C2 :cond (fn [_ _] false)}
                                                           {:actions (fn [ctx _] (assoc ctx :count 4)) :target [:B] :cond (fn [_ _] true)}]
                                                     :EC4 [{:actions (fn [ctx _] (assoc ctx :count 5)) :target :C2 :cond (fn [_ _] true)}
                                                           {:actions (fn [ctx _] (assoc ctx :count 99)) :target [:B] :cond (fn [_ _] false)}]}}
                                           :C2 {}}}}})
       (transition
        machine
        {:_state :A :initial 1}
        [:E1 {}])
       := {:_state :B :initial 1 :count 2}

       (transition
        machine
        {:_state :B :initial 1}
        [:GE1 {}])
       := {:_state :C :initial 1 :count 3}

       (transition
        machine
        {:_state [:C :C1] :initial 1}
        [:EC2 {}])
       := {:_state [:C :C2] :initial 1 :count 1}

       (transition
        machine
        {:_state [:C :C1] :initial 1}
        [:EC3 {}])
       := {:_state :B :initial 1 :count 4}

       (transition
        machine
        {:_state [:C :C1] :initial 1}
        [:EC4 {}])
       := {:_state [:C :C2] :initial 1 :count 5})

(tests
 "transition should pass context and event params to the condition evaluation functions"
 (def invocations (atom []))
 (defn condition-fn [ctx ev]
   (do
     (swap! invocations conj [ctx ev])
     true))

 (transition
  {:states {:A {:on {:EC2 [{:target :B
                            :cond condition-fn}]}}
            :B {}}}
  {:_state :A :something "here"}
  [:EC2 {:event "params"}]) := {:_state :B :something "here"}

 @invocations := [[{:something "here"} {:event "params"}]])

(tests
 "transition fails when there is no matching condition, warns but doen't do anything"
 (def invocations (atom []))
 (with-redefs [warn #(swap! invocations conj %)]
   (transition
    {:states {:A {:on {:EC2 [{:target :B
                              :cond (fn [_ _] false)}
                             {:target :C
                              :cond (fn [_ _] false)}]}}
              :B {}
              :C {}}}
    {:_state :A}
    [:EC2 {}]) := {:_state :A}

   (-> @invocations
       first
       (clojure.string/starts-with? "No matching condition for the event :EC2")) := true

   (reset! invocations [])

   "transition fails when current state invalid , warns but doen't do anything"
   (transition
    {:states {:A {}}}
    {:_state :not-exists}
    [:EC2 {}])
   := {:_state :not-exists}

   (-> @invocations
       first
       (clojure.string/starts-with?
        "Event :EC2 not found!.")) := true

   (reset! invocations [])

   "transition fails when event invalid , warns but doen't do anything"

   (transition
    {:states {:A {}}}
    {:_state :A}
    [:NOT-EXISTING {}]) :=  {:_state :A}

   (-> @invocations
       first
       (clojure.string/starts-with? "Event :NOT-EXISTING not found!."))
   := true))

(tests
 "transition should handle invoke on state entry"
 (def machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx]
                         {:processed true})
                  :on-done {:target :su
                            :actions (fn [ctx result]
                                       (assoc ctx
                                              :processed (:processed result)
                                              :done true))}
                  :on-error {:target :er
                             :actions (fn [ctx error]
                                        (assoc ctx :error error))}}}
     :su {}
     :er {}}})

 (transition
  machine
  {:_state :a}
  [:START {}])
 := {:_state :su
     :processed true
     :done true}

 "transition should handle invoke on state entry"
 (def double-invoke-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx]
                         {:processed-a true})
                  :on-done {:target :c
                            :actions (fn [ctx result]
                                       (assoc ctx
                                              :processed-a (:processed-a result)
                                              :done true))}}}
     :c {:invoke {:src (fn [_ctx]
                         {:processed-b true})
                  :on-done {:target :su
                            :actions (fn [ctx result]
                                       (assoc ctx
                                              :processed-b (:processed-b result)
                                              :done true))}}}
     :su {}
     :er {}}})

 (transition
  double-invoke-machine
  {:_state :a}
  [:START {}])
 := {:_state :su
     :processed-a true
     :processed-b true
     :done true}

 "transition should handle invoke errors"
 (def error-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_]
                         (throw (ex-info "Failed" {:reason :test})))
                  :on-error {:target :e
                             :actions (fn [ctx error]
                                        (assoc ctx :error error))}}}
     :e {}}})

 (transition
  error-machine
  {:_state :a}
  [:START {}])
 := {:_state :e
     :error {:reason :test}}

 "transition should preserve context through invoke"
 (transition
  machine
  {:_state :a :initial "value"}
  [:START {}])
 := {:_state :su
     :initial "value"
     :processed true
     :done true})

(tests
 "transition should handle minimal invoke configurations"

 (def no-handlers-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx]
                         {:processed true})}}
     :su {}
     :er {}}})

 "Should stay in same state if no handlers defined"
 (transition
  no-handlers-machine
  {:_state :a}
  [:START {}])
 := {:_state :b}

 (def no-error-handler-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx]
                         {:processed true})  ; This result gets merged into context
                  :on-done :su}}           ; Then we transition to :su
     :su {}
     :er {}}})
 "Should handle success with just on-done target"
 (transition
  no-error-handler-machine
  {:_state :a}
  [:START {}])
 := {:_state :su}

 (def error-no-handler-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx]
                         (throw (ex-info "Failed" {:reason :test})))}}
     :su {}
     :er {}}})
 "Should stay in same state on error with no handler"
 (transition
  error-no-handler-machine
  {:_state :a}
  [:START {}])
 := {:_state :b})

(tests
 "transition should handle invoke with on-done targeting hierarchical state (sub-machine entry)"

 "Invoke with on-done as bare vector (bare vector form)"
 (def invoke-to-sub-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx] {:processed true})
                  :on-done [:region :sub-state]}}
     :region {:states {:sub-state {}}}}})

 (transition
  invoke-to-sub-machine
  {:_state :a}
  [:START {}])
 := {:_state [:region :sub-state]}

 "Invoke with on-done targeting sub-state that has its own invoke (chained invoke)"
 (def invoke-to-sub-with-invoke
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_ctx] {:root true})
                  :on-done [:region :init]}}
     :region {:states
              {:init {:invoke {:src (fn [_ctx] {:sub true})
                               :on-done {:target :displayed
                                         :actions (fn [ctx result]
                                                    (assoc ctx :sub-result (:sub result)))}}}
               :displayed {}}}}})

 (transition
  invoke-to-sub-with-invoke
  {:_state :a}
  [:START {}])
 := {:_state [:region :displayed]
     :sub-result true}

 "on-error with vector target"
 (def invoke-error-to-sub-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] (throw (ex-info "Failed" {:reason :test})))
                  :on-error [:region :error]}}
     :region {:states {:error {}}}}})

 (transition
  invoke-error-to-sub-machine
  {:_state :a}
  [:START {}])
 := {:_state [:region :error]})

(tests
 "conditional on-done: first matching condition wins"
 (def cond-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:route :fast})
                  :on-done [{:target :fast-path
                             :cond (fn [_ctx result] (= (:route result) :fast))}
                            {:target :slow-path}]}}
     :fast-path {}
     :slow-path {}}})

 (transition cond-machine {:_state :a} [:START {}])
 := {:_state :fast-path}

 "conditional on-done: falls through to unconditional branch"
 (def cond-fallthrough-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:route :other})
                  :on-done [{:target :fast-path
                             :cond (fn [_ctx result] (= (:route result) :fast))}
                            {:target :slow-path}]}}
     :fast-path {}
     :slow-path {}}})

 (transition cond-fallthrough-machine {:_state :a} [:START {}])
 := {:_state :slow-path}

 "conditional on-done: cond receives context (without state key) and invoke result"
 (def cond-invocations (atom []))
 (def cond-inspect-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:data 42})
                  :on-done [{:target :ok
                             :cond (fn [ctx result]
                                     (swap! cond-invocations conj [ctx result])
                                     true)}]}}
     :ok {}}})

 (reset! cond-invocations [])
 (transition cond-inspect-machine {:_state :a :extra "ctx"} [:START {}])
 := {:_state :ok :extra "ctx"}
 @cond-invocations := [[{:extra "ctx"} {:data 42}]]

 "conditional on-done: each branch can have its own action"
 (def cond-actions-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:val 1})
                  :on-done [{:target :branch-a
                             :cond (fn [_ result] (= (:val result) 1))
                             :actions (fn [ctx result] (assoc ctx :chosen :a :val (:val result)))}
                            {:target :branch-b
                             :actions (fn [ctx result] (assoc ctx :chosen :b))}]}}
     :branch-a {}
     :branch-b {}}})

 (transition cond-actions-machine {:_state :a} [:START {}])
 := {:_state :branch-a :chosen :a :val 1}

 "conditional on-done: second branch action runs when first cond fails"
 (def cond-actions-machine-b
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:val 2})
                  :on-done [{:target :branch-a
                             :cond (fn [_ result] (= (:val result) 1))
                             :actions (fn [ctx result] (assoc ctx :chosen :a))}
                            {:target :branch-b
                             :actions (fn [ctx result] (assoc ctx :chosen :b :val (:val result)))}]}}
     :branch-a {}
     :branch-b {}}})

 (transition cond-actions-machine-b {:_state :a} [:START {}])
 := {:_state :branch-b :chosen :b :val 2}

 "conditional on-done: target can be a hierarchical state path"
 (def cond-hierarchical-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:deep true})
                  :on-done [{:target [:region :sub]
                             :cond (fn [_ result] (:deep result))}
                            {:target :flat}]}}
     :flat {}
     :region {:states {:sub {}}}}})

 (transition cond-hierarchical-machine {:_state :a} [:START {}])
 := {:_state [:region :sub]}

 "conditional on-done: target with chained invoke"
 (def cond-chained-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:go true})
                  :on-done [{:target :c
                             :cond (fn [_ result] (:go result))
                             :actions (fn [ctx result] (assoc ctx :step1 true))}
                            {:target :fallback}]}}
     :c {:invoke {:src (fn [_] {:loaded true})
                  :on-done {:target :done
                            :actions (fn [ctx result] (assoc ctx :step2 (:loaded result)))}}}
     :done {}
     :fallback {}}})

 (transition cond-chained-machine {:_state :a} [:START {}])
 := {:_state :done :step1 true :step2 true}

 "conditional on-done: no matching condition stays in current state"
 (def cond-no-match-machine
   {:states
    {:a {:on {:START {:target :b}}}
     :b {:invoke {:src (fn [_] {:val 99})
                  :on-done [{:target :x :cond (fn [_ _] false)}
                            {:target :y :cond (fn [_ _] false)}]}}
     :x {}
     :y {}}})

 (transition cond-no-match-machine {:_state :a} [:START {}])
 := {:_state :b})
