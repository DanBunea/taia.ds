(ns taia.ds.state-machines.mock-helpers
  (:require
   [clojure.walk :as walk]
   [hyperfiddle.rcf :refer [tests]]))

(defn- fn->var
  "Given a fn value, searches all loaded namespaces for its var.
   Returns nil for closures/anonymous fns that have no corresponding var."
  [f]
  (->> (all-ns)
       (some (fn [ns]
               (->> (ns-interns ns)
                    (some (fn [[_ v]]
                            (when (= (var-get v) f)
                              v))))))))

(defn make-machine-testable
  "Walks any machine map and replaces named function values with their var
   references. Closures (e.g. from higher-order action builders) are left
   as-is. With vars in the machine, with-redefs can intercept calls at
   invocation time rather than the captured value at definition time."
  [machine]
  (walk/postwalk
   (fn [x] (if (fn? x) (or (fn->var x) x) x))
   machine))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(defn- named-action [ctx _] ctx)

(defn- make-closure-action []
  (fn [ctx _] ctx))

(tests
 "named defn is replaced with its var"
 (let [machine {:states {:a {:on {:E {:actions named-action}}}}}
       result  (make-machine-testable machine)]
   (get-in result [:states :a :on :E :actions])) := #'named-action

 "closure from higher-order fn is left as-is (no var)"
 (let [closure (make-closure-action)
       machine {:states {:a {:on {:E {:actions closure}}}}}
       result  (make-machine-testable machine)]
   (fn? (get-in result [:states :a :on :E :actions]))) := true

 "non-fn values in the machine are preserved unchanged"
 (let [machine {:id :my-machine :initial :a :states {:a {:on {:E :b}} :b {}}}]
   (make-machine-testable machine)) := {:id :my-machine :initial :a :states {:a {:on {:E :b}} :b {}}})
