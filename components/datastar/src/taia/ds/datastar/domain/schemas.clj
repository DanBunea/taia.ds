(ns taia.ds.datastar.domain.schemas
  "Malli schemas for the Datastar component pipeline context."
  (:require [malli.core :as m]))

(def State
  "FSM state: keyword (flat) or vector of keywords (hierarchical)."
  (m/schema [:or :keyword [:vector :keyword]]))

(def ComponentCtx
  "The context map flowing through pipeline / state machine / component render.
   Open map — components freely add domain-specific keys."
  (m/schema
   [:map
    [:state State]
    [:memory :map]
    [:component-id :string]]))
