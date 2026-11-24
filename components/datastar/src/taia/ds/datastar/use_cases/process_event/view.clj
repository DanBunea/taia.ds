(ns taia.ds.datastar.use-cases.process-event.view
  "The `:view` proc of a component's flow: rendering.

   It calls the component's render fn with the ctx the controller emitted
   (`:from-controller`) and puts the result, as `:html` (hiccup or an effects
   list), on the flow's out-chan (the `:to-render` out-port). An error
   message from the controller passes through untouched."
  (:require
   [clojure.core.async.flow :as a.flow]
   [taia.ds.datastar.use-cases.process-event.common :as common]
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn render
  "`msg` with `:html`: the render fn (a var, dereferenced now so REPL redefs
   are picked up) applied to its `:ctx`. A render that throws becomes an
   error message; an error message passes through."
  [component-fn-var msg]
  (if (common/error-of msg)
    msg
    (log/with-context (or (:mulog-ctx msg) {})
      (try
        (assoc msg :html (log/trace-when (log/trace-enabled? :verbose (log/log-level))
                                         ::render-component []
                                         (@component-fn-var (:ctx msg))))
        (catch Throwable e
          (common/error e msg))))))

;;; Tests — render

(tests
 (def ^:private view-fixture (fn [ctx] [:div "state=" (str (:state ctx))]))
 (def ^:private p (promise))

 "render: the render fn gets the ctx; its result is added as :html, the rest
  of the message (the event, the reply, …) kept"
 (render #'view-fixture {:event ["go" {}] :ctx {:state :displayed} :reply p})
 := {:event ["go" {}] :ctx {:state :displayed} :reply p :html [:div "state=" ":displayed"]}

 "render: an effects list is :html too, verbatim"
 (def ^:private effects-fixture (fn [_] [[:execute-script "window.location.href='/x'"]]))
 (render #'effects-fixture {:ctx {}})
 := {:ctx {} :html [[:execute-script "window.location.href='/x'"]]}

 "render: a render fn that throws → an error message, carrying the reply"
 (def ^:private bad-fixture (fn [_] (throw (ex-info "render failed" {}))))
 (-> (render #'bad-fixture {:ctx {} :reply p})
     (update ::common/error ex-message))
 := {::common/error "render failed" :mulog-ctx nil :reply p}

 "render: an error message from the controller passes through, not rendered"
 (def ^:private boom (ex-info "boom" {}))
 (render #'view-fixture {::common/error boom :mulog-ctx nil :reply p})
 := {::common/error boom :mulog-ctx nil :reply p}

 "render: traced (:verbose), under the message's trace — the result is the same"
 (with-redefs [log/log-level (constantly :verbose)]
   (render #'view-fixture {:ctx       {:state :displayed}
                           :mulog-ctx {:mulog/root-trace :a-root :mulog/parent-trace :a-parent}}))
 := {:ctx       {:state :displayed}
     :mulog-ctx {:mulog/root-trace :a-root :mulog/parent-trace :a-parent}
     :html      [:div "state=" ":displayed"]}

 "render: traced (:verbose), a render fn that throws → still an error message"
 (with-redefs [log/log-level (constantly :verbose)]
   (-> (render #'bad-fixture {:ctx {}})
       (update ::common/error ex-message)))
 := {::common/error "render failed" :mulog-ctx nil})

(defn step
  "Step fn of the `:view` proc (see `clojure.core.async.flow/process`)."
  ([] {:params   {:component-fn-var "the component's render fn var"
                  :out-chan         "external channel renders leave on"}
       :ins      {:from-controller "{:event :initial-ctx :ctx}"}
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

;;; Tests — step: its four arities. The transform's work is render's (tested
;;; above), so here it is a spy.

(tests
 (def ^:private any-view (fn [_] [:p]))
 (def ^:private out-ch (clojure.core.async/chan))

 "describe (0): its params, its one in, a :mixed workload"
 (-> (step)
     (update :params (comp set keys))
     (update :ins (comp set keys)))
 := {:params   #{:component-fn-var :out-chan}
     :ins      #{:from-controller}
     :workload :mixed}

 "init (1): the out-chan becomes an out-port"
 (step {:component-fn-var #'any-view :out-chan out-ch})
 := {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}

 "transition (2): resume / pause / stop leave the state as it is"
 (-> {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
     (step ::a.flow/resume))
 := {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
 (-> {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
     (step ::a.flow/pause))
 := {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
 (-> {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
     (step ::a.flow/stop))
 := {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}

 "transform (3): renders the controller's message with the component's render
  fn and sends the result on :to-render; the state is unchanged"
 (with-redefs [render (spy/spy (constantly {:html :rendered}))]
   (-> {:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
       (step :from-controller {:ctx {:state :displayed}}))
   := [{:component-fn-var #'any-view ::a.flow/out-ports {:to-render out-ch}}
       {:to-render [{:html :rendered}]}]
   (spy/called-once-with? render #'any-view {:ctx {:state :displayed}}) := true))
