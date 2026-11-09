(ns taia.ds.state-machines.domain
  "Malli schemas for state machine data structures.
   Available on both JVM and ClojureScript via reader conditionals —
   Malli requires :clj for fn? predicates, so those are guarded."
  (:require #?(:clj [malli.core :as m])))

#?(:clj
   (def State
     "State can be a keyword (flat) or a vector of keywords (hierarchical)."
     (m/schema [:or :keyword [:vector :keyword]])))

#?(:clj
   (def Event
     "An event is a tuple of [keyword params-map]."
     (m/schema [:tuple :keyword :map])))

#?(:clj
   (def TransitionOpts
     (m/schema
      [:map
       [:state-key {:optional true} :keyword]
       [:trace-fn {:optional true} fn?]
       [:action-trace-fn {:optional true} fn?]])))

#?(:clj
   (def MachineDefinition
     "Top-level machine shape. Intentionally open — machines contain deeply nested
      :states/:on/:invoke structures with function values that are impractical to fully schema."
     (m/schema
      [:map
       [:id {:optional true} :keyword]
       [:initial {:optional true} State]
       [:states {:optional true} :map]
       [:on {:optional true} :map]])))

#?(:clj
   (def Context
     "The context map that flows through state machine transitions — both as input
      (from-state) and output (transition result). Always contains a state value
      (keyword or vector of keywords) under a configurable key — :_state by default,
      :state when used by the datastar pipeline. Also carries :memory and any
      domain-specific keys added by actions. The :invoke key is always stripped
      from transition output.
      Open map — components freely extend the context with domain keys."
     (m/schema
      [:map
       [:memory {:optional true} :map]])))
