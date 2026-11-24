(ns taia.ds.datastar.adapters.flow.controller
  "The `:controller` proc of a component flow: the state machine.

   It reads events from the flow's in-chan (an in-port), builds the ctx the
   event transitions from — the request's ctx, merged with the ctx it holds
   when the flow keeps one (`merge-held-ctx`) — runs the machine, and emits the
   transitioned pipeline map on `:to-view`."
  (:require
   [clojure.core.async.flow :as a.flow]
   [taia.ds.datastar.adapters.flow.message :as message]
   [taia.ds.datastar.adapters.handlers.pipeline :as pipeline]
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]))

;;; Held ctx + client signals

(def ^:private per-request-keys
  "ctx keys that describe the request, not the component: they always come
   from the newest request."
  [:logged-user :app-config :request-method])

(defn merge-held-ctx
  "The ctx an event transitions from in a flow that holds one.

   - No held ctx (the first event, or a per-request flow) → `initial-ctx`, the
     ctx built from the request exactly as the one-shot pipeline builds it.
   - Otherwise the held ctx is the base:
     - `:memory` is the held memory with the client's signals merged over it —
       client-owned values (form inputs) win;
     - `:state` is the held state — while the flow lives the server is the
       authority on the FSM position, and the client's echo can be stale after
       a server push;
     - the per-request keys (`:logged-user`, `:app-config`, `:request-method`)
       come from the new request when there is one; a server-sent event keeps
       the held ones, minus `:request-method`.

   Server-owned values should therefore live OUTSIDE `:memory` (top-level ctx
   keys), so a stale signal echo can never overwrite them."
  [held-ctx initial-ctx request?]
  (if-not held-ctx
    initial-ctx
    (-> (if request?
          (merge (apply dissoc held-ctx per-request-keys)
                 (select-keys initial-ctx per-request-keys))
          (dissoc held-ctx :request-method))
        (dissoc :from-state)
        (assoc :memory (merge (:memory held-ctx) (:memory initial-ctx))))))

(tests
 "merge-held-ctx: nothing held → the request's ctx verbatim"
 (merge-held-ctx nil {:state :idle :memory {:a 1}} true) := {:state :idle :memory {:a 1}}

 "merge-held-ctx: held state wins over the client's echo; client memory merged
  over held memory; server-owned top-level keys survive"
 (merge-held-ctx {:state :running :memory {:a 1 :b 2} :remaining 3 :from-state :idle
                  :logged-user {:user/id "old"}}
                 {:state :idle :memory {:b 20 :c 30} :logged-user {:user/id "u1"}
                  :request-method :post}
                 true)
 := {:state :running :memory {:a 1 :b 20 :c 30} :remaining 3
     :logged-user {:user/id "u1"} :request-method :post}

 "merge-held-ctx: a server event (no request) keeps the held per-request keys
  but drops :request-method"
 (merge-held-ctx {:state :running :memory {:a 1} :logged-user {:user/id "u1"}
                  :request-method :get}
                 {:state :idle :memory {} :logged-user nil}
                 false)
 := {:state :running :memory {:a 1} :logged-user {:user/id "u1"}})

;;; The proc

(defn- handle-event
  "One event through the state machine. Returns [held-ctx' out-msg]."
  [{:keys [machine-var component-name component-id controller-name held-ctx keep-ctx?]}
   {:keys [event request mulog-ctx] :as msg}]
  (log/with-context (or mulog-ctx {})
    (try
      (let [m   (-> {:request         request
                     :component-name  component-name
                     :component-id    component-id
                     :controller-name controller-name
                     :machine-var     machine-var
                     :event           event
                     :mulog-ctx       mulog-ctx}
                    pipeline/assoc-initial-ctx-and-state
                    (update :initial-ctx #(merge-held-ctx held-ctx % (some? request)))
                    pipeline/assoc-process-event-results)
            out (pipeline/assoc-removed-signal-nils m)]
        [(when keep-ctx? (dissoc (:ctx m) :from-state))
         (message/with-reply out msg)])
      (catch Throwable e
        [held-ctx (message/error e msg)]))))

(defn step
  "Step fn of the `:controller` proc (see `clojure.core.async.flow/process`)."
  ([] {:params   {:machine-var     "the controller's `machine` var"
                  :component-name  "component name, as discovered"
                  :component-id    "the instance id"
                  :controller-name "explicit controller name, or nil"
                  :in-chan         "external channel events arrive on"
                  :keep-ctx?       "hold the ctx between events (connected components)"}
       :outs     {:to-view "the transitioned pipeline map, for the view"}
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
