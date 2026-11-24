(ns taia.ds.datastar.adapters.handlers.request
  "From a Ring request to the plain event message a component's flow takes
   (see `taia.ds.datastar.use-cases.process-event.common`). This is the only
   place that knows where Datastar puts things in a request: the event's
   params, the component's slice of the signal store, the logged user."
  (:require
   [charred.api :as charred]
   [taia.ds.extensions.interface :as ext]
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn event
  "[event-name params]: the params are the query/form params plus every key of
   the JSON body that is not this component's own slice (so other components'
   signals ride along), minus the plumbing keys (`:logged-user`,
   `:component-name`, and the raw `:datastar` GET store)."
  [req component-id event-name]
  [event-name (-> (:body-params req)
                  (dissoc (keyword component-id))
                  (merge (:params req))
                  (dissoc :logged-user :component-name :datastar)
                  (or {}))])

(tests
 "event: body-params (with the component's slice dropped) merged with params
  (sans :logged-user / :component-name)"
 (event {:body-params {:c1 {:count 5} :other-data "value"}
         :params      {:logged-user {:user/id "123"} :component-name :counter :other-param "param-value"}}
        "c1" "increment")
 := ["increment" {:other-data "value" :other-param "param-value"}]

 "event: the raw :datastar query param (GET signal store) never leaks into the params"
 (event {:body-params {} :params {:datastar "{\"c3\":{\"state\":\"x\"}}" :session-id "s1"}} "c3" "load")
 := ["load" {:session-id "s1"}]

 "event: nothing at all → empty params"
 (event {} "c1" "init") := ["init" {}])

(defn signals
  "The component's own slice of the client's signal store, or nil. POST events
   carry the store as the JSON body (`:body-params`); GET events — e.g. an
   @get to /ds/nds-connect/… — carry the SAME store as the `datastar` query
   param. A present body wins."
  [req component-id]
  (let [cid (keyword component-id)]
    (or (get-in req [:body-params cid])
        (some-> (get-in req [:params :datastar])
                (charred/read-json :key-fn keyword)
                (get cid)))))

(tests
 "signals: the body's slice for this component"
 (signals {:body-params {:c1 {:count 5 :state :active}}} "c1") := {:count 5 :state :active}

 "signals: a GET carries the store as the datastar query param"
 (signals {:params {:datastar "{\"c6\":{\"count\":7,\"state\":\"displaying\"}}"}} "c6")
 := {:count 7 :state "displaying"}

 "signals: a present body wins over the query param"
 (signals {:body-params {:c7 {:count 1}} :params {:datastar "{\"c7\":{\"count\":99}}"}} "c7") := {:count 1}

 "signals: none → nil"
 (signals {:params {}} "c8") := nil)

(defn request-ctx
  "What a request brings to the ctx besides signals: the logged user, and the
   app config (attached by the application's middleware) and HTTP method when
   present."
  [req]
  (-> {:logged-user (get-in req [:params :logged-user])}
      (ext/assoc-if :app-config (:app-config req) (:app-config req))
      (ext/assoc-if :request-method (:request-method req) (:request-method req))))

(tests
 "request-ctx: the logged user, the app config and the HTTP method"
 (request-ctx {:params {:logged-user {:user/id "u1"}} :app-config {:env :dev} :request-method :post})
 := {:logged-user {:user/id "u1"} :app-config {:env :dev} :request-method :post}

 "request-ctx: no app config, no method → those keys stay absent"
 (request-ctx {:params {}}) := {:logged-user nil})

(defn ->event-msg
  "The flow's in-chan message for `event-name` arriving with `req`; the
   caller's trace rides along."
  [req component-id event-name]
  {:event       (event req component-id event-name)
   :signals     (signals req component-id)
   :request-ctx (request-ctx req)
   :mulog-ctx   (log/local-context)})

;;; Tests — ->event-msg: each part is built by its own fn (tested above) or
;;; the logging, so here they are spies.

(tests
 "->event-msg: the event, the component's signals and the request ctx, built
  from the request; the caller's trace rides along"
 (with-redefs [event             (spy/spy (constantly ["toggle" {:id "3"}]))
               signals           (spy/spy (constantly {:count 1 :state "displayed"}))
               request-ctx       (spy/spy (constantly {:logged-user {:user/id "u1"}}))
               log/local-context (spy/spy (constantly {:t 1}))]
   (-> {:body-params {:c1 {:count 1 :state "displayed"}}
        :params      {:logged-user {:user/id "u1"} :id "3"}}
       (->event-msg "c1" "toggle"))
   := {:event       ["toggle" {:id "3"}]
       :signals     {:count 1 :state "displayed"}
       :request-ctx {:logged-user {:user/id "u1"}}
       :mulog-ctx   {:t 1}}
   (spy/called-once-with? event
                          {:body-params {:c1 {:count 1 :state "displayed"}}
                           :params      {:logged-user {:user/id "u1"} :id "3"}}
                          "c1"
                          "toggle")
   := true
   (spy/called-once-with? signals
                          {:body-params {:c1 {:count 1 :state "displayed"}}
                           :params      {:logged-user {:user/id "u1"} :id "3"}}
                          "c1")
   := true
   (spy/called-once-with? request-ctx
                          {:body-params {:c1 {:count 1 :state "displayed"}}
                           :params      {:logged-user {:user/id "u1"} :id "3"}})
   := true
   (spy/called-once-with? log/local-context) := true))
