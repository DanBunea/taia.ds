(ns taia.ds.state-machines.test-helpers
  "Declarative tests for hierarchical state machines: expectations map, ordered trace
  of action/invoke labels. Invoke :src is not run; each id uses :invoke-returns if present,
  else {}. Returns :ok or failure maps for RCF."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.state-machines.interface :as sm]
   [spy.core :as spy])
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
(defn- th-invoke-src [_] {:loaded true})

;;; Tests — fn->keyword

(tests
 "a named fn: its name, kebab-cased"
 (fn->keyword th-named-action :E1) := :th-named-action

 "a var: the name in its metadata"
 (fn->keyword #'th-named-action :E1) := :th-named-action

 "an anonymous fn: :on-<event>"
 (fn->keyword (fn [ctx _] ctx) :E1) := :on-E1

 "no fn: nil"
 (fn->keyword nil :E1) := nil)

;;; Tests — normalize-expected

(tests
 "a keyword is shorthand for {:target k}"
 (normalize-expected :b) := {:target :b}

 "a vector is shorthand for {:target path}"
 (normalize-expected [:c :c2]) := {:target [:c :c2]}

 "a map is taken as it is"
 (normalize-expected {:target :b :trace [:x]}) := {:target :b :trace [:x]}

 "anything else throws"
 (normalize-expected "b") :throws #?(:clj ExceptionInfo :cljs ExceptionInfo))

;;; Tests — resolve-invoke-mock: the value a traced invoke returns.

(tests
 "an id listed in :invoke-returns: its value"
 (resolve-invoke-mock :load {:invoke-returns {:load {:ok true}}}) := {:ok true}

 "an id not listed: {}"
 (resolve-invoke-mock :other {:invoke-returns {:load {:ok true}}}) := {}

 "no :invoke-returns at all: {}"
 (resolve-invoke-mock :load {}) := {}

 "an ex-info listed: it is thrown, so the machine takes :on-error"
 (resolve-invoke-mock :load {:invoke-returns {:load (ex-info "fail" {:reason :test})}})
 :throws #?(:clj ExceptionInfo :cljs ExceptionInfo))

;;; Tests — run-single-transition. The machine is not run: sm/transition is a
;;; spy (transition's semantics are tested in hierarchical-state-machine), and
;;; so are fn->keyword and resolve-invoke-mock (tested above).

(tests
 (defn- without-trace-fns
   "A call to sm/transition, its opts without the trace fns (closures)."
   [[machine from-state event opts]]
   [machine from-state event (dissoc opts :action-trace-fn :trace-fn)])

 "a target-only expectation that holds: sm/transition gets the machine, the
  from-state under :_state, the event with {} params, and the tracing opts"
 (with-redefs [sm/transition (spy/stub {:_state :b})]
   (run-single-transition {:id :any} :a :E1 :b {})
   := {:from :a :event :E1 :expected-target :b :actual-target :b :pass? true}
   (spy/called-once? sm/transition) := true
   (-> sm/transition
       spy/first-call
       without-trace-fns)
   := [{:id :any} {:_state :a} [:E1 {}] {:state-key :_state}])

 "a hierarchical from-state path goes in as it is; a {:target} map is an
  expectation too"
 (with-redefs [sm/transition (spy/stub {:_state [:c :c2]})]
   (run-single-transition {:id :any} [:c :c1] :EC1 {:target [:c :c2]} {})
   := {:from [:c :c1] :event :EC1 :expected-target [:c :c2] :actual-target [:c :c2] :pass? true}
   (-> sm/transition
       spy/first-call
       without-trace-fns)
   := [{:id :any} {:_state [:c :c1]} [:EC1 {}] {:state-key :_state}])

 "a wrong target: not :pass?"
 (with-redefs [sm/transition (spy/stub {:_state :b})]
   (run-single-transition {:id :any} :a :E1 :c {})
   := {:from :a :event :E1 :expected-target :c :actual-target :b :pass? false})

 "the :state-key opt: the from-state and the actual target live under it"
 (with-redefs [sm/transition (spy/stub {:aa :b})]
   (run-single-transition {:id :any} :a :E1 :b {:state-key :aa})
   := {:from :a :event :E1 :expected-target :b :actual-target :b :pass? true}
   (-> sm/transition
       spy/first-call
       without-trace-fns)
   := [{:id :any} {:aa :a} [:E1 {}] {:state-key :aa}])

 "the opts' :ctx and the expectation's :ctx are merged into the from-state
  (the expectation's wins); the expectation's :event-params go with the event"
 (with-redefs [sm/transition (spy/stub {:_state :left})]
   (run-single-transition {:id :any}
                          :a
                          :PICK
                          {:target :left :ctx {:side :left} :event-params {:x 1}}
                          {:ctx {:side :right :user "u1"}})
   := {:from :a :event :PICK :expected-target :left :actual-target :left :pass? true}
   (-> sm/transition
       spy/first-call
       without-trace-fns)
   := [{:id :any} {:side :left :user "u1" :_state :a} [:PICK {:x 1}] {:state-key :_state}]))

(tests
 (defn- machine-tracing
   "A stand-in for sm/transition, for a machine whose event runs an action
    then enters a state with an invoke: it calls the opts' trace fns as
    transition would, lands in :b, and returns what they gave back."
   [_machine _from-state [event-name params] {:keys [state-key action-trace-fn trace-fn]}]
   (let [action-gave (action-trace-fn event-name th-named-action {:c 1} params)
         invoke-gave (trace-fn :load-invoke th-invoke-src {:c 1})]
     {state-key    :b
      :action-gave action-gave
      :invoke-gave invoke-gave}))

 "tracing: an action is not run but labelled (by fn->keyword) into the trace,
  the ctx going back unchanged; an invoke's :src is not run but traced by its
  id and answered by resolve-invoke-mock; target and trace hold: :pass?"
 (with-redefs [sm/transition       (spy/spy machine-tracing)
               fn->keyword         (spy/stub :th-named-action)
               resolve-invoke-mock (spy/stub {:loaded true})]
   (run-single-transition {:id :any}
                          :a
                          :E1
                          {:target :b :trace [:th-named-action :load-invoke]}
                          {:invoke-returns {:load-invoke {:loaded true}}})
   := {:from            :a
       :event           :E1
       :expected-target :b
       :actual-target   :b
       :expected-trace  [:th-named-action :load-invoke]
       :actual-trace    [:th-named-action :load-invoke]
       :pass?           true}
   (spy/first-response sm/transition) := {:_state :b :action-gave {:c 1} :invoke-gave {:loaded true}}
   (spy/called-once-with? fn->keyword th-named-action :E1) := true
   (spy/called-once-with? resolve-invoke-mock :load-invoke {:invoke-returns {:load-invoke {:loaded true}}}) := true)

 "a wrong trace: not :pass?, even though the target holds"
 (with-redefs [sm/transition       (spy/spy machine-tracing)
               fn->keyword         (spy/stub :th-named-action)
               resolve-invoke-mock (spy/stub {})]
   (run-single-transition {:id :any} :a :E1 {:target :b :trace [:wrong]} {})
   := {:from            :a
       :event           :E1
       :expected-target :b
       :actual-target   :b
       :expected-trace  [:wrong]
       :actual-trace    [:th-named-action :load-invoke]
       :pass?           false}))

;;; Tests — test-machine. Each (from, event) pair is run-single-transition's
;;; (tested above), so here it is a spy.

(tests
 "all pass: :ok; run-single-transition runs each (from, event) pair, in order,
  with the opts (default {})"
 (with-redefs [run-single-transition (spy/stub {:pass? true})]
   (test-machine {:a      {:E1 :b
                           :E2 {:target :c :trace [:x]}}
                  [:c :c1] {:EC1 [:c :c2]}}
                 {:id :some})
   := :ok
   (spy/calls run-single-transition)
   := [[{:id :some} :a :E1 :b {}]
       [{:id :some} :a :E2 {:target :c :trace [:x]} {}]
       [{:id :some} [:c :c1] :EC1 [:c :c2] {}]])

 "the opts given go to every run"
 (with-redefs [run-single-transition (spy/stub {:pass? true})]
   (test-machine {:a {:E1 :b}} {:id :some} {:state-key :aa :invoke-returns {:i {}}})
   := :ok
   (spy/called-once-with? run-single-transition {:id :some} :a :E1 :b {:state-key :aa :invoke-returns {:i {}}})
   := true)

 "a from-state with no events (a terminal state, documented) runs nothing"
 (with-redefs [run-single-transition (spy/stub {:pass? true})]
   (test-machine {:z {}} {:id :some})
   := :ok
   (spy/not-called? run-single-transition) := true)

 "failures: a vector of the runs that did not pass, without :pass?"
 (with-redefs [run-single-transition (spy/spy
                                      (fn [_machine from event _expected _opts]
                                        (if (= event :E2)
                                          {:from from :event event :expected-target :c :actual-target :b :pass? false}
                                          {:from from :event event :expected-target :b :actual-target :b :pass? true})))]
   (test-machine {:a {:E1 :b :E2 :c}} {:id :some})
   := [{:from :a :event :E2 :expected-target :c :actual-target :b}]))
