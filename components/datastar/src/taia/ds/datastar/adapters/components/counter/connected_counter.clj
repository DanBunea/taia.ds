(ns taia.ds.datastar.adapters.components.counter.connected-counter
  "Connected counter example: the page opens an SSE stream to this component
   (/ds/nds-connect/.../init) and the initial render arrives as a :patch over
   that stream; clicks post one-shot events to /ds/nds/... as usual. The open
   stream is what `send-ui!` pushes to — see the `comment` block in `dev`."
  (:require
   [taia.ds.datastar.adapters.handlers.context :as c]))

(defn connected-counter [{:keys [component-id] :as ctx}]
  [[:patch
    [:div.counter-container
     {:id component-id
      :data-signals (c/ctx->data-signals ctx)}
     [:h3 "Counter Component"]
     [:div.count-display
      {:style {:font-size "24px"
               :font-weight "bold"
               :margin "20px 0"}}
      (str "Count: " (get-in ctx [:memory :count]))]
     [:button.btn.contained
      {"data-on:click" (str "@post('/ds/nds/counter.connected-counter/" component-id "/increment')")
       :type "button"
       :style {:padding "10px 20px"
               :font-size "16px"}}
      "Increment"]]
    {:d*/selector (str "#" component-id)}]])

