(ns taia.ds.datastar.adapters.components.html-page
  "Minimal page head for the example components. Applications ship their own
   head; the only requirement is loading /js/datastar.js (served from the
   `datastar` classpath resources via ring's wrap-resource).")

(defn page-head []
  [:head
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:title "taia.ds"]
   [:script {:type "module" :src "/js/datastar.js"}]
   [:script {:src "/js/sse-reconnect.js" :defer true}]])
