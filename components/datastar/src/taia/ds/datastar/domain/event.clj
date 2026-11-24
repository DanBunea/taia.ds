(ns taia.ds.datastar.domain.event
  "An event against a component's state machine: its keyword, whether it is
   one of Datastar's own, and the transition it causes. Pure: the machine's
   actions are the component's, and tracing hooks are passed in by the caller."
  (:require
   [clojure.string :as str]
   [taia.ds.datastar.domain.ctx :as ctx]
   [taia.ds.state-machines.interface :as sm]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn event-keyword
  "The machine's keyword for an event name: upper-cased (`increment` → `:INCREMENT`)."
  [event-name]
  (keyword (str/upper-case (name event-name))))

(tests
 "event-keyword: upper-cased, as a keyword"
 (event-keyword "increment") := :INCREMENT
 (event-keyword "save_edit") := :SAVE_EDIT
 (event-keyword "RENDERER.LOADED") := :RENDERER.LOADED)

(defn internal-event?
  "Datastar's own events (RENDERER.*, INSTALLHOOK.*), sent by the client-side
   library: no transition."
  [event-name]
  (let [n (name (event-keyword event-name))]
    (or (str/starts-with? n "RENDERER.")
        (str/starts-with? n "INSTALLHOOK."))))

(tests
 "internal-event?: Datastar's RENDERER.* / INSTALLHOOK.*, in any case"
 (internal-event? "renderer.loaded") := true
 (internal-event? "INSTALLHOOK.READY") := true
 "internal-event?: a component's own event is not"
 (internal-event? "increment") := false)

(defn transition
  "Runs [event-name params] through `machine` from `ctx` and returns the new
   ctx with `:from-state` (the state it left, as keywords). An internal
   Datastar event returns `ctx` unchanged. `opts` are passed to the state
   machine (e.g. `:trace-fn` / `:action-trace-fn`), plus `:state-key :state`."
  ([machine ctx event] (transition machine ctx event {}))
  ([machine ctx [event-name params] opts]
   (if (internal-event? event-name)
     ctx
     (let [from (update ctx :state ctx/coerce-state)]
       (-> (sm/transition machine from [(event-keyword event-name) params] (assoc opts :state-key :state))
           (assoc :from-state (:state from)))))))

;;; Tests — transition: coercing the state is ctx/coerce-state's work and the
;;; transition itself the state machine's (both tested in their own nss), so
;;; here they are spies.

(tests
 "transition: the state (a string, as signals send it) is coerced; the
  machine gets the upper-cased event and the opts plus :state-key :state; the
  state it left is stamped as :from-state"
 (with-redefs [ctx/coerce-state (spy/spy (constantly :idle))
               sm/transition    (spy/spy (constantly {:state :done :memory {:got {:x 1}}}))]
   (transition {:id :t :initial :idle :states {:idle {} :done {}}}
               {:state "idle" :memory {}}
               ["go" {:x 1}]
               {:trace-fn :a-trace-fn})
   := {:state :done :memory {:got {:x 1}} :from-state :idle}
   (spy/called-once-with? ctx/coerce-state "idle") := true
   (spy/called-once-with? sm/transition
                          {:id :t :initial :idle :states {:idle {} :done {}}}
                          {:state :idle :memory {}}
                          [:GO {:x 1}]
                          {:trace-fn :a-trace-fn :state-key :state})
   := true)

 "transition: without opts the machine gets just :state-key :state"
 (with-redefs [ctx/coerce-state (spy/spy (constantly :idle))
               sm/transition    (spy/spy (constantly {:state :idle :memory {:n 2}}))]
   (transition {:id :t :initial :idle :states {:idle {} :done {}}} {:state :idle :memory {:n 1}} ["stay" {}])
   := {:state :idle :memory {:n 2} :from-state :idle}
   (spy/called-once-with? sm/transition
                          {:id :t :initial :idle :states {:idle {} :done {}}}
                          {:state :idle :memory {:n 1}}
                          [:STAY {}]
                          {:state-key :state})
   := true)

 "transition: an internal Datastar event leaves the ctx untouched; the
  machine is not run"
 (with-redefs [ctx/coerce-state (spy/spy)
               sm/transition    (spy/spy)]
   (transition {:id :t :initial :idle :states {:idle {} :done {}}} {:state "idle" :memory {}} ["RENDERER.LOADED" {}])
   := {:state "idle" :memory {}}
   (spy/not-called? sm/transition) := true
   (spy/not-called? ctx/coerce-state) := true))
