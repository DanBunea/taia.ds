(ns taia.ds.state-machines.test-helpers
  "Declarative tests for hierarchical state machines: expectations map, ordered trace
  of action/invoke labels. Invoke :src is not run; each id uses :invoke-returns if present,
  else {}. Returns :ok or failure maps for RCF."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.state-machines.interface :as sm])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(defn- fallback-action-kw [event-name]
  (keyword (str "on-" (name event-name))))

(defn- anonymous-segment? [s]
  (boolean
   (or (re-matches #"fn__\d+" s)
       (re-matches #"fn--?\d+" s)
       (re-matches #"eval\d+" s))))

(defn- segment->kw [s]
  (keyword (str/replace s #"_" "-")))

(defn fn->keyword
  "Best-effort label for a function: named defn/closure parent name, else :on-<event>.
  When `f` is a Var (e.g. `#'foo/bar` used in machine `:src`/`:actions` so reloads
  pick up redefinitions), reads the name from var metadata.
  JVM: only segments after the first `$` are function names (first segment is namespace)."
  [f event-name]
  (when f
    (if (var? f)
      (let [nm (some-> f meta :name)]
        (if nm
          (segment->kw (str nm))
          (fallback-action-kw event-name)))
      #?(:clj
         (let [^Class c (class f)
               raw (.getName c)
               segments (str/split raw #"\$")
               tail (if (> (count segments) 1) (rest segments) segments)
               meaningful (->> tail reverse (remove anonymous-segment?) first)]
           (if meaningful
             (segment->kw meaningful)
             (fallback-action-kw event-name)))
         :cljs
         (let [n (try (.-name f) (catch :default _ nil))]
           (if (and (string? n) (not (str/blank? n)))
             (let [parts (str/split n #"\$")
                   tail (if (> (count parts) 1) (rest parts) parts)
                   meaningful (->> tail reverse (remove anonymous-segment?) first)]
               (if meaningful
                 (segment->kw meaningful)
                 (fallback-action-kw event-name)))
             (fallback-action-kw event-name)))))))

(defn- normalize-expected [expected]
  (cond
    (map? expected) expected
    (or (keyword? expected) (vector? expected)) {:target expected}
    :else (throw (ex-info "Invalid expected form" {:expected expected}))))

(defn- actual-target [result state-key]
  (state-key result))

(defn- resolve-invoke-mock [invoke-id opts]
  "Value returned for an invoke when using trace-fn (real :src not called).
  Uses :invoke-returns for invoke-id, else {}."
  (let [m (or (:invoke-returns opts) {})
        v (if (contains? m invoke-id)
            (get m invoke-id)
            {})]
    (if (instance? #?(:clj ExceptionInfo :cljs ExceptionInfo) v)
      (throw v)
      v)))

(defn run-single-transition
  "Runs one transition with tracing. Returns
  {:from :event :expected-target :actual-target
   :expected-trace :actual-trace :pass? ...}"
  [machine from-path event-name expected opts]
  (let [state-key (or (:state-key opts) :_state)
        normalized (normalize-expected expected)
        expected-target (:target normalized)
        expected-trace (:trace normalized)
        event-params (or (:event-params normalized) {})
        ctx-merge (merge (:ctx opts) (:ctx normalized))
        from-state (merge ctx-merge {state-key from-path})
        trace! (atom [])
        action-trace-fn
        (fn [ev action-fn ctx _params]
          (swap! trace! conj (fn->keyword action-fn ev))
          ctx)
        trace-fn
        (fn [invoke-id _src-fn _ctx]
          (swap! trace! conj invoke-id)
          (resolve-invoke-mock invoke-id opts))
        transition-opts {:state-key state-key
                         :action-trace-fn action-trace-fn
                         :trace-fn trace-fn}
        result (sm/transition machine from-state [event-name event-params] transition-opts)
        actual-t (actual-target result state-key)
        actual-tr @trace!
        target-ok? (= expected-target actual-t)
        trace-ok? (if expected-trace
                    (= expected-trace actual-tr)
                    true)
        pass? (and target-ok? trace-ok?)]
    (cond-> {:from from-path
             :event event-name
             :expected-target expected-target
             :actual-target actual-t
             :pass? pass?}
      expected-trace (assoc :expected-trace expected-trace
                            :actual-trace actual-tr))))

(defn test-machine
  "Expectations: {from-state {event expected-result ...} ...}
  expected-result: keyword/vector (target only) or {:target ... :trace [...] :ctx ... :event-params ...}.
  opts may include :state-key (default :_state), :ctx merged into the transition context,
  and :invoke-returns {invoke-id value | ex-info} for mocked invokes (unlisted ids get {}).
  Returns :ok or a vector of failure maps."
  [expectations machine & [opts]]
  (let [opts (or opts {})
        failures
        (vec
         (for [[from-path events] expectations
               [event-name expected] events
               :let [res (run-single-transition machine from-path event-name expected opts)]
               :when (not (:pass? res))]
           (dissoc res :pass?)))]
    (if (seq failures)
      failures
      :ok)))

;; Top-level fns so RCF does not instrument them (stable fn->keyword labels).
(defn- th-named-action [ctx _] (assoc ctx :x 1))
(defn- th-on-done-act [ctx _r] (assoc ctx :done true))
(defn- th-on-err-act [ctx e] (assoc ctx :err e))
(defn- th-act-a [ctx r] (assoc ctx :a (:v r)))
(defn- th-act-b [ctx r] (assoc ctx :b (:v r)))

(tests
 "simple transition, target only shorthand"
 (test-machine
  {:a {:E1 :b}}
  {:states {:a {:on {:E1 :b}} :b {}}})
 := :ok

 "simple transition with map target only"
 (test-machine
  {:a {:E1 {:target :b}}}
  {:states {:a {:on {:E1 {:target :b}}} :b {}}})
 := :ok

 "named action trace"
 (test-machine
  {:a {:E1 {:target :b :trace [:th-named-action]}}}
  {:states {:a {:on {:E1 {:actions th-named-action :target :b}}} :b {}}})
 := :ok

 "anonymous action falls back to :on-E1"
 (test-machine
  {:a {:E1 {:target :b :trace [:on-E1]}}}
  {:states {:a {:on {:E1 {:actions (fn [ctx _] ctx) :target :b}}} :b {}}})
 := :ok

 "hierarchical state path"
 (test-machine
  {[:c :c1] {:EC1 {:target [:c :c2]}}}
  {:states {:c {:states {:c1 {:on {:EC1 {:target :c2}}}
                         :c2 {}}}}})
 := :ok

 "invoke success: trace invoke id then on-done action"
 (test-machine
  {:a {:START {:target :su :trace [:load-invoke :th-on-done-act]}}}
  {:states {:a {:on {:START {:target :b}}}
            :b {:invoke {:id :load-invoke
                         :src (fn [_] {:ok true})
                         :on-done {:target :su :actions th-on-done-act}}}
            :su {}}}
  {:invoke-returns {:load-invoke {:ok true}}})
 := :ok

 "invoke error path"
 (test-machine
  {:a {:START {:target :er :trace [:fail-invoke :th-on-err-act]}}}
  {:states {:a {:on {:START {:target :b}}}
            :b {:invoke {:id :fail-invoke
                         :src (fn [_] (throw (ex-info "x" {})))
                         :on-error {:target :er :actions th-on-err-act}}}
            :er {}}}
  {:invoke-returns {:fail-invoke (ex-info "fail" {:reason :test})}})
 := :ok

 "chained invokes"
 (test-machine
  {:a {:START {:target :done
               :trace [:inv-a :th-act-a :inv-b :th-act-b]}}}
  {:states {:a {:on {:START {:target :b}}}
            :b {:invoke {:id :inv-a
                         :src (fn [_] {:v 1})
                         :on-done {:target :c :actions th-act-a}}}
            :c {:invoke {:id :inv-b
                         :src (fn [_] {:v 2})
                         :on-done {:target :done :actions th-act-b}}}
            :done {}}}
  {:invoke-returns {:inv-a {:v 1} :inv-b {:v 2}}})
 := :ok

 "conditional event branch via :ctx and :event-params"
 (test-machine
  {:a {:PICK {:target :left :ctx {:side :left} :event-params {}}}}
  {:states {:a {:on {:PICK [{:target :left :cond (fn [ctx _] (= :left (:side ctx)))}
                            {:target :right}]}}
            :left {} :right {}}})
 := :ok

 "terminal state documented as empty map is no-op"
 (test-machine {:z {} :a {:E1 :b}}
               {:states {:a {:on {:E1 :b}} :b {} :z {}}})
 := :ok

 "failure: wrong target"
 (test-machine
  {:a {:E1 :c}}
  {:states {:a {:on {:E1 :b}} :b {} :c {}}})
 := [{:from :a :event :E1 :expected-target :c :actual-target :b}]

 "failure: wrong trace"
 (test-machine
  {:a {:E1 {:target :b :trace [:wrong]}}}
  {:states {:a {:on {:E1 {:actions th-named-action :target :b}}} :b {}}})
 := [{:from :a
      :event :E1
      :expected-target :b
      :actual-target :b
      :expected-trace [:wrong]
      :actual-trace [:th-named-action]}])
