(ns taia.ds.state-machines.interface
  (:require
   [taia.ds.state-machines.hierarchical-state-machine :as hsm]
   #?(:clj [taia.ds.state-machines.domain :as domain])
   #?(:clj [taia.ds.state-machines.mock-helpers :as mock-helpers])
   #?(:clj [malli.experimental :as mx])))

#?(:clj
   (do
     (def MachineDefinition
       "Malli schema for a machine definition map (:id, :initial, :states, :on)."
       domain/MachineDefinition)
     (def Event
       "Malli schema for a transition event tuple [event-keyword params-map]."
       domain/Event)
     (def TransitionOpts
       "Malli schema for transition options (:state-key, :trace-fn, :action-trace-fn)."
       domain/TransitionOpts)
     (def Context
       "Malli schema for the context map flowing through transitions (state + :memory)."
       domain/Context)))

#?(:clj
   (mx/defn transition :- domain/Context
     "Runs a state machine transition. Returns the updated context map containing
      the new state (under a configurable key, :_state by default) and :memory.
      The :invoke key is always stripped from the result.
      May throw if the machine has no matching transition for the event."
     ([machine :- domain/MachineDefinition
       from-state :- domain/Context
       [event-name event-params] :- domain/Event]
      (hsm/transition machine from-state [event-name event-params]))
     ([machine :- domain/MachineDefinition
       from-state :- domain/Context
       [event-name event-params] :- domain/Event
       opts :- domain/TransitionOpts]
      (hsm/transition machine from-state [event-name event-params] opts)))

   :cljs
   (defn transition
     "Runs a state machine transition. Returns the updated context map containing
      the new state (under a configurable key, :_state by default) and :memory."
     ([machine from-state [event-name event-params]]
      (hsm/transition machine from-state [event-name event-params]))
     ([machine from-state [event-name event-params] opts]
      (hsm/transition machine from-state [event-name event-params] opts))))

#?(:clj
   (def make-machine-testable
     "Replaces named fn values in a machine with their vars so with-redefs can intercept them in tests."
     mock-helpers/make-machine-testable))
