(ns taia.ds.datastar.adapters.flow.view
  "The `:view` proc of a component flow: rendering.

   It renders the pipeline map the controller emitted (`:from-controller`)
   with the component's render fn and puts the result on the flow's out-chan
   (the `:to-render` out-port). An error message from the controller passes
   through untouched."
  (:require
   [clojure.core.async.flow :as a.flow]
   [taia.ds.datastar.adapters.flow.message :as message]
   [taia.ds.datastar.adapters.handlers.pipeline :as pipeline]
   [taia.ds.logging.interface :as log]))

(defn- render
  [component-fn-var msg]
  (if (message/error-of msg)
    msg
    (log/with-context (or (:mulog-ctx msg) {})
      (try
        (pipeline/assoc-html (assoc msg :component-fn-var component-fn-var))
        (catch Throwable e
          (message/error e msg))))))

(defn step
  "Step fn of the `:view` proc (see `clojure.core.async.flow/process`)."
  ([] {:params   {:component-fn-var "the component's render fn var"
                  :out-chan         "external channel renders leave on"}
       :ins      {:from-controller "the transitioned pipeline map"}
       :workload :mixed})
  ([{:keys [out-chan] :as args}]
   (-> args
       (dissoc :out-chan)
       (assoc ::a.flow/out-ports {:to-render out-chan})))
  ([state _transition] state)
  ([{:keys [component-fn-var] :as state} _in msg]
   [state {:to-render [(render component-fn-var msg)]}]))

(def proc
  "The proc launcher. Built once (a launcher is stateless until started) from
   the `step` var, so a REPL redef reaches flows started afterwards."
  (a.flow/process #'step))
