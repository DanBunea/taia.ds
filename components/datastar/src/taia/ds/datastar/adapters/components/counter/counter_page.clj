(ns taia.ds.datastar.adapters.components.counter.counter-page
  "Counter page component that renders the counter using data-on-load."
  (:require
   [taia.ds.datastar.adapters.components.html-page :as hp]

   [taia.ds.datastar.adapters.handlers.context :as c]))

(defn counter-page
  "Counter page component that renders the counter using data-on-load."
  [ctx]
  [:html {:lang "en"}
   (hp/page-head)
   [:body
    [:div#cp1
     {:data-signals (c/ctx->data-signals ctx)}
     [:h1 "Counter Page"]
     [:div.counter-wrapper
      {"id"        "c1"
       "data-init" (str "@post('/ds/nds/counter.counter/c1/init')")}]]]])

