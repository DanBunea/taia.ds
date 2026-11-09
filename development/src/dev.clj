(ns dev
  "Development entry point: a small Ring/reitit server that mounts the
   datastar handlers and serves the example counter pages.

     bb dev                       ; or: clojure -M:dev -m dev
     http://localhost:7788/        ; counter (one-shot /ds/nds events)
     http://localhost:7788/connected ; connected counter (/ds/nds-connect SSE)

   This mirrors what an application base has to do: routes with the
   component/component-id/event path params, params + JSON body parsing, a
   :logged-user with a :user/id, the static assets, and an async Jetty."
  (:require
   [hyperfiddle.rcf]
   [muuntaja.core :as m]
   [reitit.coercion.malli :as malli]
   [reitit.ring :as reitit]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.muuntaja :as rrmm]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [taia.ds.datastar.adapters.handlers.response :refer [->html]]
   [taia.ds.datastar.interface :as ds]))

(def port 7788)

(defn- wrap-logged-user
  "Stand-in for an application's auth middleware: the handlers key SSE
   connections by [:params :logged-user :user/id]."
  [handler]
  (fn
    ([req] (handler (assoc-in req [:params :logged-user] {:user/id "dev"})))
    ([req respond raise] (handler (assoc-in req [:params :logged-user] {:user/id "dev"}) respond raise))))

(defn- page-handler
  "Serves a component's full-page INIT render at a canonical URL."
  [component-name component-id]
  (fn [req]
    (let [{:keys [html]} (ds/process-component-event req component-name component-id "INIT" nil)]
      (->html html))))

(def path-params
  {:component :string :component-id :string :event :string})

(defn router []
  (reitit/router
   [["/" {:get {:handler (page-handler "counter.counter-page" "cp1")}}]
    ["/connected" {:get {:handler (page-handler "counter.connected-counter-page" "cc1")}}]
    ["/ds"
     ["/nds"
      ["/:component/:component-id/:controller/:event"
       {:parameters {:path (assoc path-params :controller :string)}
        :handler    ds/handler-component-event}]
      ["/:component/:component-id/:event"
       {:parameters {:path path-params}
        :handler    ds/handler-component-event}]]
     ["/nds-connect"
      ["/:component/:component-id/:event"
       {:parameters {:path path-params}
        :handler    ds/handler-connected-component-connect}]]]]
   {:data {:muuntaja   m/instance
           :coercion   malli/coercion
           :middleware [rrmm/format-middleware
                        rrc/coerce-exceptions-middleware
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware]}}))

(defn app []
  (-> (reitit/ring-handler (router) (reitit/create-default-handler))
      wrap-logged-user
      wrap-keyword-params
      wrap-params
      (wrap-resource "datastar")))

(defonce !server (atom nil))

(defn stop! []
  (when-let [s @!server]
    (.stop s)
    (reset! !server nil)))

(defn start! []
  (stop!)
  (hyperfiddle.rcf/enable!)
  (ds/set-component-search-paths! ["taia.ds.datastar.adapters.components"])
  (reset! !server (jetty/run-jetty (app) {:port port :join? false :async? true}))
  (println (str "taia.ds dev server on http://localhost:" port "/  (and /connected)"))
  @!server)

(comment
  ;; push a server-initiated patch to the connected counter page
  (ds/send-ui! "cc1" "dev" [[:patch [:div#cc1 "pushed from the REPL"]]]))

(defn -main [& _]
  (start!)
  @(promise))
