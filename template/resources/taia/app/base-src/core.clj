(ns {{top/ns}}.{{main/ns}}.web.core
  "The application's HTTP wiring — what a base must do to host taia.ds
   components:

   1. mount the handlers on the route shapes under /ds (nds-connect on any
      method: every request there opens a connection for `send-event!`);
   2. parse params and JSON bodies (muuntaja) so the pipeline sees
      `:body-params` / `:params`;
   3. put a `:logged-user` with a `:user/id` on `:params` — SSE connections are
      keyed by it (here a `?user=` stand-in: replace it with your auth);
   4. serve the static assets: `wrap-resource \"datastar\"` + `wrap-content-type`
      (datastar.js ships in the taia.ds jar);
   5. run an ASYNC Jetty — the connect handler is `[req respond raise]` only."
  (:require
   [muuntaja.core :as m]
   [reitit.coercion.malli :as malli]
   [reitit.ring :as reitit]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.muuntaja :as rrmm]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as response]
   [taia.ds.datastar.interface :as ds]
   [{{top/ns}}.{{main/ns}}.web.components.home.home :as home])
  (:gen-class))

(def search-paths
  "Where discovery looks for `<group>.<name>` components: the component
   `basics.counter` is `{{top/ns}}.{{main/ns}}.web.components.basics.counter`
   (+ its `-controller`). The order is the lookup order."
  ["{{top/ns}}.{{main/ns}}.web.components"])

(defn- user-of
  "Stand-in for authentication: the `?user=` query param, default demo-user.
   Open the live counter in two tabs as two users to see each tab's stream."
  [req]
  (or (not-empty (get-in req [:params :user])) "demo-user"))

(defn wrap-logged-user
  "Puts `{:user/id …}` under [:params :logged-user], in both Ring arities."
  [handler]
  (letfn [(with-user [req] (assoc-in req [:params :logged-user] {:user/id (user-of req)}))]
    (fn
      ([req] (handler (with-user req)))
      ([req respond raise] (handler (with-user req) respond raise)))))

(def ^:private path-params
  {:component :string :component-id :string :event :string})

(defn- to-home
  "GET / → the home page. Jetty runs async, so a plain handler needs both arities."
  ([_] (response/redirect home/url))
  ([req respond _] (respond (to-home req))))

(defn router []
  (reitit/router
   [["/" {:get {:handler to-home}}]
    ["/ds"
     ["/nds"
      ["/:component/:component-id/:controller/:event"
       {:parameters {:path (assoc path-params :controller :string)}
        :handler    ds/handler-component-event}]
      ["/:component/:component-id/:event"
       {:parameters {:path path-params}
        :handler    ds/handler-component-event}]]
     ["/nds-connect"
      ;; any method: every request opens a connection (an SSE stream and the
      ;; flow `send-event!` reaches); clicks go to /ds/nds
      ["/:component/:component-id/:event"
       {:parameters {:path path-params}
        :handler    ds/handler-connected-component-connect}]]]]
   {:data {:muuntaja   m/instance
           :coercion   malli/coercion
           :middleware [rrmm/format-middleware
                        rrc/coerce-exceptions-middleware
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware]}}))

(defn app
  "The Ring handler. Middleware order matters: params before the logged-user
   stand-in (it reads `?user=`), resources + content-type outermost."
  []
  (-> (reitit/ring-handler (router) (reitit/create-default-handler))
      wrap-logged-user
      wrap-keyword-params
      wrap-params
      (wrap-resource "datastar")
      wrap-content-type))

(defonce !server (atom nil))

(defn stop! []
  (when-let [s @!server]
    (.stop s)
    (reset! !server nil)))

(defn start!
  "Starts the app on `port` (async Jetty) and returns the URL of the home
   page. There is no page route: the page is the `home.home` component, and a
   GET on its own INIT route renders it. Idempotent."
  [port]
  (stop!)
  (ds/set-component-search-paths! search-paths)
  (reset! !server (jetty/run-jetty (app) {:port port :join? false :async? true}))
  (let [url (str "http://localhost:" port home/url)]
    (println "{{main}}:" url)
    url))

(defn -main
  "Production entry point (the uberjar's main): the app on $PORT, default {{port}}."
  [& _]
  (start! (or (some-> (System/getenv "PORT") parse-long) {{port}}))
  @(promise))
