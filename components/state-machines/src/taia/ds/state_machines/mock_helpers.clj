(ns taia.ds.state-machines.mock-helpers
  (:require
   [clojure.walk :as walk]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

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

;;; fn->var

(tests
 "a fn some var holds: that var"
 (fn->var named-action) := #'named-action

 "a closure (no var holds it): nil"
 (fn->var (make-closure-action)) := nil)

;;; make-machine-testable. Finding a fn's var is fn->var's (tested above), so
;;; here it is a spy.

(tests
 "a fn in the machine is replaced by the var fn->var finds for it"
 (with-redefs [fn->var (spy/stub #'named-action)]
   (make-machine-testable {:states {:a {:on {:E {:actions named-action}}}}})
   := {:states {:a {:on {:E {:actions #'named-action}}}}}
   (spy/called-once-with? fn->var named-action) := true)

 "a fn fn->var finds no var for (a closure from a higher-order fn) is left as it is"
 ;; a def because identity matters: each make-closure-action call is a new fn
 (def ^:private closure-action (make-closure-action))
 (with-redefs [fn->var (spy/stub nil)]
   (make-machine-testable {:states {:a {:on {:E {:actions closure-action}}}}})
   := {:states {:a {:on {:E {:actions closure-action}}}}}
   (spy/called-once-with? fn->var closure-action) := true)

 "a machine with no fns: unchanged, fn->var never asked"
 (with-redefs [fn->var (spy/stub nil)]
   (make-machine-testable {:id :my-machine :initial :a :states {:a {:on {:E :b}} :b {}}})
   := {:id :my-machine :initial :a :states {:a {:on {:E :b}} :b {}}}
   (spy/not-called? fn->var) := true))
