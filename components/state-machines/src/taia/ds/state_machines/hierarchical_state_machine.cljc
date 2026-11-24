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

;;; Tests — state-key-from-opts

(tests
 "the :state-key given"
 (state-key-from-opts {:state-key :aa}) := :aa

 "none given: :_state"
 (state-key-from-opts {}) := :_state)

;;; Tests — transition. Real machines in, ctx out: the machine semantics are
;;; transition's own behaviour, so nothing in it is spied but the user's fns
;;; (conditions, trace fns) and `warn`.

(tests
 "transition from a state to another — every kind of target, flat and nested"
 (def ^:private machine
   {:on {:GE1 :B
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

 "no context — a state's event with a bare keyword target"
 (transition machine {:_state :A} [:E1 {}])
 := {:_state :B}

 "no context — the state lives under the :state-key given"
 (transition machine {:aa :A} [:E1 {}] {:state-key :aa})
 := {:aa :B}

 "no context — a {:target} map"
 (transition machine {:_state :B} [:E2 {}])
 := {:_state :C}

 "no context — an empty map: stays"
 (transition machine {:_state :B} [:E3 {}])
 := {:_state :B}

 "no context — a machine-level (global) event, bare keyword"
 (transition machine {:_state :A} [:GE1 {}])
 := {:_state :B}

 "no context — a global event, {:target} map"
 (transition machine {:_state :B} [:GE2 {}])
 := {:_state :C}

 "no context — a global event, empty map: stays"
 (transition machine {:_state :B} [:GE3 {}])
 := {:_state :B}

 "no context — a nested state, a bare keyword target: a sibling"
 (transition machine {:_state [:C :C1]} [:EC1 {}])
 := {:_state [:C :C2]}

 "no context — a nested state, a vector target: an absolute path"
 (transition machine {:_state [:C :C2]} [:EC2 {}])
 := {:_state :B}

 "no context — a nested state, an empty map: stays"
 (transition machine {:_state [:C :C2]} [:EC3 {}])
 := {:_state [:C :C2]}

 "no context — a nested state, a {:target} keyword: a sibling"
 (transition machine {:_state [:C :C2]} [:EC4 {}])
 := {:_state [:C :C3]}

 "no context — a nested state, a {:target} vector: an absolute path"
 (transition machine {:_state [:C :C2]} [:EC5 {}])
 := {:_state [:C :C1]}

 "no context — options: the second, whose :cond holds"
 (transition machine {:_state [:C :C2]} [:EC6 {}])
 := {:_state :B}

 "no context — options: the first, whose :cond holds"
 (transition machine {:_state [:C :C2]} [:EC7 {}])
 := {:_state [:C :C3]}

 "no context — options: one with no :cond always holds"
 (transition machine {:_state [:C :C2]} [:EC8 {}])
 := {:_state :B}

 "no context — options, under the :state-key given"
 (transition machine {:x [:C :C2]} [:EC8 {}] {:state-key :x})
 := {:x :B}

 "with context — the context sent is kept through every kind of transition"
 (transition machine {:_state :A :something "here"} [:E1 {}])
 := {:_state :B :something "here"}

 (transition machine {:_state :B :something "here"} [:E2 {}])
 := {:_state :C :something "here"}

 (transition machine {:_state :B :something "here"} [:E3 {}])
 := {:_state :B :something "here"}

 (transition machine {:_state :A :something "here"} [:GE1 {}])
 := {:_state :B :something "here"}

 (transition machine {:_state :B :something "here"} [:GE2 {}])
 := {:_state :C :something "here"}

 (transition machine {:_state :B :something "here"} [:GE3 {}])
 := {:_state :B :something "here"}

 (transition machine {:_state [:C :C1] :something "here"} [:EC1 {}])
 := {:_state [:C :C2] :something "here"}

 (transition machine {:_state [:C :C2] :something "here"} [:EC2 {}])
 := {:_state :B :something "here"}

 (transition machine {:_state [:C :C2] :something "here"} [:EC3 {}])
 := {:_state [:C :C2] :something "here"}

 (transition machine {:_state [:C :C2] :something "here"} [:EC4 {}])
 := {:_state [:C :C3] :something "here"}

 (transition machine {:_state [:C :C2] :something "here"} [:EC5 {}])
 := {:_state [:C :C1] :something "here"}

 (transition machine {:_state [:C :C2] :something "here"} [:EC6 {}])
 := {:_state :B :something "here"}

 (transition machine {:_state [:C :C2] :something "here"} [:EC7 {}])
 := {:_state [:C :C3] :something "here"})

(tests
 "transition from a state to another running the action"
 (def ^:private machine
   {:on {:GE1 {:actions (fn [ctx _] (assoc ctx :count 3)) :target :C}}
    :states {:A {:on {:E1 {:actions (fn [ctx _] (assoc ctx :count 2)) :target :B}}}
             :B {:on {}}
             :C {:states {:C1 {:on {:EC2 {:actions (fn [ctx _] (assoc ctx :count 1)) :target :C2}
                                    :EC3 [{:actions (fn [ctx _] (assoc ctx :count 77)) :target :C2 :cond (fn [_ _] false)}
                                          {:actions (fn [ctx _] (assoc ctx :count 4)) :target [:B] :cond (fn [_ _] true)}]
                                    :EC4 [{:actions (fn [ctx _] (assoc ctx :count 5)) :target :C2 :cond (fn [_ _] true)}
                                          {:actions (fn [ctx _] (assoc ctx :count 99)) :target [:B] :cond (fn [_ _] false)}]}}
                          :C2 {}}}}})

 "actions — a state's event runs its action"
 (transition machine {:_state :A} [:E1 {}])
 := {:_state :B :count 2}

 "actions — a global event runs its action"
 (transition machine {:_state :B} [:GE1 {}])
 := {:_state :C :count 3}

 "actions — a nested state's event runs its action"
 (transition machine {:_state [:C :C1]} [:EC2 {}])
 := {:_state [:C :C2] :count 1}

 "actions — options: only the chosen option's action runs (the second)"
 (transition machine {:_state [:C :C1]} [:EC3 {}])
 := {:_state :B :count 4}

 "actions — options: only the chosen option's action runs (the first)"
 (transition machine {:_state [:C :C1]} [:EC4 {}])
 := {:_state [:C :C2] :count 5}

 "actions with context — the action gets the context sent and adds to it"
 (transition machine {:_state :A :initial 1} [:E1 {}])
 := {:_state :B :initial 1 :count 2}

 (transition machine {:_state :B :initial 1} [:GE1 {}])
 := {:_state :C :initial 1 :count 3}

 (transition machine {:_state [:C :C1] :initial 1} [:EC2 {}])
 := {:_state [:C :C2] :initial 1 :count 1}

 (transition machine {:_state [:C :C1] :initial 1} [:EC3 {}])
 := {:_state :B :initial 1 :count 4}

 (transition machine {:_state [:C :C1] :initial 1} [:EC4 {}])
 := {:_state [:C :C2] :initial 1 :count 5})

(tests
 "no :target — an event with only :actions stays in its state, the action applied"
 (transition {:states {:A {:on {:BUMP {:actions (fn [ctx _] (update ctx :count inc))}}}
                       :B {}}}
             {:_state :A :count 1}
             [:BUMP {}])
 := {:_state :A :count 2})

(tests
 "a :cond gets the context (without the state key) and the event params"
 (def ^:private params-cond (spy/spy (fn [_ctx _ev] true)))
 (transition {:states {:A {:on {:EC2 [{:target :B :cond params-cond}]}}
                       :B {}}}
             {:_state :A :something "here"}
             [:EC2 {:event "params"}])
 := {:_state :B :something "here"}
 (spy/called-once-with? params-cond {:something "here"} {:event "params"}) := true)

(tests
 "no matching condition: it warns, and the state is left as it was"
 ;; a def because identity matters: the warning prints this very machine, its
 ;; cond fns (object ids) and all
 (def ^:private machine
   {:states {:A {:on {:EC2 [{:target :B :cond (fn [_ _] false)}
                            {:target :C :cond (fn [_ _] false)}]}}
             :B {}
             :C {}}})
 (with-redefs [warn (spy/spy)]
   (transition machine {:_state :A} [:EC2 {}])
   := {:_state :A}
   (spy/called-once-with? warn (str "No matching condition for the event :EC2. Event params {}\n. Machine: "
                                    (prn-str machine)))
   := true))

(tests
 "a current state the machine does not have: it warns, and the state is left as it was"
 (with-redefs [warn (spy/spy)]
   (transition {:states {:A {}}} {:_state :not-exists} [:EC2 {}])
   := {:_state :not-exists}
   (spy/called-once-with? warn (str "Event :EC2 not found!. Event params {}\n. "
                                    "Paths: [:on :EC2]\n or (:states :not-exists :on :EC2)\n don't exist in machine: "))
   := true)

 "an event the state does not have: it warns, and the state is left as it was"
 (with-redefs [warn (spy/spy)]
   (transition {:states {:A {}}} {:_state :A} [:NOT-EXISTING {}])
   := {:_state :A}
   (spy/called-once-with? warn (str "Event :NOT-EXISTING not found!. Event params {}\n. "
                                    "Paths: [:on :NOT-EXISTING]\n or (:states :A :on :NOT-EXISTING)\n don't exist in machine: "))
   := true))

(tests
 "invoke on state entry: its :src runs, then :on-done's action and target"
 (transition {:states
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
               :er {}}}
             {:_state :a}
             [:START {}])
 := {:_state :su
     :processed true
     :done true}

 "invoke on state entry: the context is preserved through it"
 (transition {:states
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
               :er {}}}
             {:_state :a :initial "value"}
             [:START {}])
 := {:_state :su
     :initial "value"
     :processed true
     :done true}

 "chained invokes: :on-done enters a state with its own invoke, which runs too"
 (transition {:states
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
               :er {}}}
             {:_state :a}
             [:START {}])
 := {:_state :su
     :processed-a true
     :processed-b true
     :done true}

 "an invoke whose :src throws an ex-info: :on-error's action gets its ex-data"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_]
                                   (throw (ex-info "Failed" {:reason :test})))
                            :on-error {:target :e
                                       :actions (fn [ctx error]
                                                  (assoc ctx :error error))}}}
               :e {}}}
             {:_state :a}
             [:START {}])
 := {:_state :e
     :error {:reason :test}})

(tests
 "minimal invoke — no handlers: it stays in the invoking state"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_ctx]
                                   {:processed true})}}
               :su {}
               :er {}}}
             {:_state :a}
             [:START {}])
 := {:_state :b}

 "minimal invoke — :on-done as a bare target: it goes there, the result not merged"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_ctx]
                                   {:processed true})
                            :on-done :su}}
               :su {}
               :er {}}}
             {:_state :a}
             [:START {}])
 := {:_state :su}

 "minimal invoke — an error with no :on-error: it stays in the invoking state"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_ctx]
                                   (throw (ex-info "Failed" {:reason :test})))}}
               :su {}
               :er {}}}
             {:_state :a}
             [:START {}])
 := {:_state :b})

(tests
 "invoke into a sub-machine — :on-done as a bare vector: a hierarchical state"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_ctx] {:processed true})
                            :on-done [:region :sub-state]}}
               :region {:states {:sub-state {}}}}}
             {:_state :a}
             [:START {}])
 := {:_state [:region :sub-state]}

 "invoke into a sub-machine — the sub-state's own invoke runs too (chained)"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_ctx] {:root true})
                            :on-done [:region :init]}}
               :region {:states
                        {:init {:invoke {:src (fn [_ctx] {:sub true})
                                         :on-done {:target :displayed
                                                   :actions (fn [ctx result]
                                                              (assoc ctx :sub-result (:sub result)))}}}
                         :displayed {}}}}}
             {:_state :a}
             [:START {}])
 := {:_state [:region :displayed]
     :sub-result true}

 "invoke into a sub-machine — :on-error as a bare vector"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] (throw (ex-info "Failed" {:reason :test})))
                            :on-error [:region :error]}}
               :region {:states {:error {}}}}}
             {:_state :a}
             [:START {}])
 := {:_state [:region :error]})

(tests
 "conditional on-done: the first matching condition wins"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:route :fast})
                            :on-done [{:target :fast-path
                                       :cond (fn [_ctx result] (= (:route result) :fast))}
                                      {:target :slow-path}]}}
               :fast-path {}
               :slow-path {}}}
             {:_state :a}
             [:START {}])
 := {:_state :fast-path}

 "conditional on-done: it falls through to the unconditional branch"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:route :other})
                            :on-done [{:target :fast-path
                                       :cond (fn [_ctx result] (= (:route result) :fast))}
                                      {:target :slow-path}]}}
               :fast-path {}
               :slow-path {}}}
             {:_state :a}
             [:START {}])
 := {:_state :slow-path}

 "conditional on-done: the cond gets the context (without the state key) and the invoke result"
 (def ^:private inspect-cond (spy/spy (fn [_ctx _result] true)))
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:data 42})
                            :on-done [{:target :ok :cond inspect-cond}]}}
               :ok {}}}
             {:_state :a :extra "ctx"}
             [:START {}])
 := {:_state :ok :extra "ctx"}
 (spy/called-once-with? inspect-cond {:extra "ctx"} {:data 42}) := true

 "conditional on-done: each branch has its own action (the first's runs)"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:val 1})
                            :on-done [{:target :branch-a
                                       :cond (fn [_ result] (= (:val result) 1))
                                       :actions (fn [ctx result] (assoc ctx :chosen :a :val (:val result)))}
                                      {:target :branch-b
                                       :actions (fn [ctx _result] (assoc ctx :chosen :b))}]}}
               :branch-a {}
               :branch-b {}}}
             {:_state :a}
             [:START {}])
 := {:_state :branch-a :chosen :a :val 1}

 "conditional on-done: the second branch's action runs when the first cond fails"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:val 2})
                            :on-done [{:target :branch-a
                                       :cond (fn [_ result] (= (:val result) 1))
                                       :actions (fn [ctx _result] (assoc ctx :chosen :a))}
                                      {:target :branch-b
                                       :actions (fn [ctx result] (assoc ctx :chosen :b :val (:val result)))}]}}
               :branch-a {}
               :branch-b {}}}
             {:_state :a}
             [:START {}])
 := {:_state :branch-b :chosen :b :val 2}

 "conditional on-done: the target can be a hierarchical state path"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:deep true})
                            :on-done [{:target [:region :sub]
                                       :cond (fn [_ result] (:deep result))}
                                      {:target :flat}]}}
               :flat {}
               :region {:states {:sub {}}}}}
             {:_state :a}
             [:START {}])
 := {:_state [:region :sub]}

 "conditional on-done: the target's own invoke runs too (chained)"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:go true})
                            :on-done [{:target :c
                                       :cond (fn [_ result] (:go result))
                                       :actions (fn [ctx _result] (assoc ctx :step1 true))}
                                      {:target :fallback}]}}
               :c {:invoke {:src (fn [_] {:loaded true})
                            :on-done {:target :done
                                      :actions (fn [ctx result] (assoc ctx :step2 (:loaded result)))}}}
               :done {}
               :fallback {}}}
             {:_state :a}
             [:START {}])
 := {:_state :done :step1 true :step2 true}

 "conditional on-done: no matching condition stays in the invoking state"
 (transition {:states
              {:a {:on {:START {:target :b}}}
               :b {:invoke {:src (fn [_] {:val 99})
                            :on-done [{:target :x :cond (fn [_ _] false)}
                                      {:target :y :cond (fn [_ _] false)}]}}
               :x {}
               :y {}}}
             {:_state :a}
             [:START {}])
 := {:_state :b})

;;; Tests — transition's trace opts: `:action-trace-fn` runs in place of each
;;; action, `:trace-fn` in place of each invoke's :src (test-helpers builds on them).
;;; The action and src fns are defs where a spy's call is asserted with them:
;;; there identity matters.

(tests
 ":action-trace-fn: called in place of the action, with the event, the action,
  the ctx and the event params; what it returns is the new ctx"
 (def ^:private bump (fn [ctx _] (assoc ctx :count 1)))
 (def ^:private traced-bump (spy/stub {:count 7}))
 (transition {:states {:A {:on {:E1 {:actions bump :target :B}}}
                       :B {}}}
             {:_state :A :n 1}
             [:E1 {:p 1}]
             {:action-trace-fn traced-bump})
 := {:_state :B :count 7}
 (spy/called-once-with? traced-bump :E1 bump {:n 1} {:p 1}) := true)

(tests
 ":trace-fn: called in place of the invoke's :src, with its :id, the src and
  the ctx (without the state key); what it returns is the invoke's result"
 (def ^:private load-src (fn [_] {:v 0}))
 (def ^:private traced-load (spy/stub {:v 1}))
 (transition {:states {:a {:on {:START {:target :b}}}
                       :b {:invoke {:id :load
                                    :src load-src
                                    :on-done {:target :done :actions (fn [ctx r] (assoc ctx :v (:v r)))}}}
                       :done {}}}
             {:_state :a :n 1}
             [:START {}]
             {:trace-fn traced-load})
 := {:_state :done :n 1 :v 1}
 (spy/called-once-with? traced-load :load load-src {:n 1}) := true

 ":trace-fn: an invoke with no :id is traced as :unknown"
 (def ^:private traced-anonymous-load (spy/stub {}))
 (transition {:states {:a {:on {:START {:target :b}}}
                       :b {:invoke {:src load-src :on-done :done}}
                       :done {}}}
             {:_state :a}
             [:START {}]
             {:trace-fn traced-anonymous-load})
 := {:_state :done}
 (spy/called-once-with? traced-anonymous-load :unknown load-src {}) := true

 ":trace-fn: an ex-info it throws takes the invoke's :on-error, with its ex-data"
 (transition {:states {:a {:on {:START {:target :b}}}
                       :b {:invoke {:id :load
                                    :src (fn [_] {:v 0})
                                    :on-done {:target :done :actions (fn [ctx r] (assoc ctx :v (:v r)))}
                                    :on-error {:target :er :actions (fn [ctx e] (assoc ctx :err e))}}}
                       :done {}
                       :er {}}}
             {:_state :a}
             [:START {}]
             {:trace-fn (fn [_id _src _ctx] (throw (ex-info "fail" {:reason :test})))})
 := {:_state :er :err {:reason :test}})

(tests
 ":action-trace-fn on an invoke's :on-done: called with :on-done, the action,
  the ctx and the invoke's result"
 (def ^:private store-v (fn [ctx r] (assoc ctx :v (:v r))))
 (def ^:private traced-store (spy/spy (fn [_ev action ctx r] (action ctx r))))
 (transition {:states {:a {:on {:START {:target :b}}}
                       :b {:invoke {:id :load
                                    :src (fn [_] {:v 0})
                                    :on-done {:target :done :actions store-v}}}
                       :done {}}}
             {:_state :a}
             [:START {}]
             {:action-trace-fn traced-store})
 := {:_state :done :v 0}
 (spy/called-once-with? traced-store :on-done store-v {} {:v 0}) := true)

(tests
 "chained invokes, traced: each invoke, then its :on-done action, in order —
  each one's ctx carries what the one before added"
 (def ^:private src-a (fn [_] {:v 1}))
 (def ^:private src-b (fn [_] {:v 2}))
 (def ^:private act-a (fn [ctx r] (assoc ctx :a (:v r))))
 (def ^:private act-b (fn [ctx r] (assoc ctx :b (:v r))))
 (def ^:private traced-chain-invoke (spy/spy (fn [_id src ctx] (src ctx))))
 (def ^:private traced-chain-action (spy/spy (fn [_ev action ctx r] (action ctx r))))

 (transition {:states {:a {:on {:START {:target :b}}}
                       :b {:invoke {:id :inv-a :src src-a :on-done {:target :c :actions act-a}}}
                       :c {:invoke {:id :inv-b :src src-b :on-done {:target :done :actions act-b}}}
                       :done {}}}
             {:_state :a}
             [:START {}]
             {:trace-fn        traced-chain-invoke
              :action-trace-fn traced-chain-action})
 := {:_state :done :a 1 :b 2}
 (spy/calls traced-chain-invoke) := [[:inv-a src-a {}]
                                     [:inv-b src-b {:a 1}]]
 (spy/calls traced-chain-action) := [[:on-done act-a {} {:v 1}]
                                     [:on-done act-b {:a 1} {:v 2}]])
