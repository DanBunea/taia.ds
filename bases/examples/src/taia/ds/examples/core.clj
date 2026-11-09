(ns taia.ds.examples.core
  "The examples application — and the reference for what an application base
   must do to host taia.ds components:

   1. mount the two handlers on the three route shapes under /ds;
   2. parse params and JSON bodies (muuntaja) so the pipeline sees
      `:body-params` / `:params`;
   3. put a `:logged-user` with a `:user/id` on `:params` — SSE connections are
      keyed by it;
   4. serve the static assets: `wrap-resource \"datastar\"` + `wrap-content-type`;
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
   [taia.ds.datastar.interface :as ds]
   [taia.ds.examples.layout :as layout]))

(def search-paths
  "Where discovery looks for `<group>.<name>` components. Applications list
   their own prefixes here; the order is the lookup order."
  ["taia.ds.examples.components"])

(defn- user-of
  "Stand-in for authentication: the `?user=` query param, default demo-user.
   The live-counter example opens two tabs as two users to show fan-out."
  [req]
  (or (not-empty (get-in req [:params :user])) "demo-user"))

(defn wrap-logged-user
  "Puts `{:user/id …}` under [:params :logged-user], in both Ring arities."
  [handler]
  (letfn [(with-user [req] (assoc-in req [:params :logged-user] {:user/id (user-of req)}))]
    (fn
      ([req] (handler (with-user req)))
      ([req respond raise] (handler (with-user req) respond raise)))))

(defn- sync->async
  "Lifts a 1-arity handler to both arities — async Jetty calls the 3-arity,
   and a sync-only handler there never completes the request."
  [f]
  (fn
    ([req] (f req))
    ([req respond raise]
     (try (respond (f req))
          (catch Throwable t (raise t))))))

(def ^:private path-params
  {:component :string :component-id :string :event :string})

(defn- index-handler [_]
  (ds/html-response (layout/index)))

(defn- example-handler [req]
  (let [slug (get-in req [:path-params :example])]
    (if-let [example (layout/find-example slug)]
      (ds/html-response (layout/example-page example (user-of req)))
      (assoc (ds/html-response (layout/not-found slug)) :status 404))))

(defn router []
  (reitit/router
   [["/" {:get {:handler (sync->async index-handler)}}]
    ["/examples/:example" {:get {:parameters {:path {:example :string}}
                                 :handler    (sync->async example-handler)}}]
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
  "Starts the examples app on `port` (async Jetty). Idempotent."
  [port]
  (stop!)
  (ds/set-component-search-paths! search-paths)
  (reset! !server (jetty/run-jetty (app) {:port port :join? false :async? true}))
  @!server)
