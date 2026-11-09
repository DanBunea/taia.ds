(ns taia.ds.datastar.adapters.handlers.context
  (:require
   [charred.api :as charred]
   [taia.ds.datastar.domain.schemas :as schemas]
   [malli.experimental :as mx]))

(def ComponentCtx schemas/ComponentCtx)

(mx/defn ctx->data-signals :- :string
  "Serialize component context to JSON for Datastar data-signals round-trip. Pure function, never throws."
  [{:keys [state memory component-id] :as ctx} :- ComponentCtx]
  (-> {}
      (assoc component-id (merge memory {:state state}))
      charred/write-json-str))