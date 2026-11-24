(ns taia.ds.datastar.adapters.handlers.default-component-handler
  "Dynamic endpoint handler for component events.
   Handles routes like /:component/:component-id/:event.

   The event runs through a per-request component flow
   (`taia.ds.datastar.adapters.flow/process-component-event`); the response
   shaping (`render-response`, `error-response`) lives in
   `taia.ds.datastar.adapters.handlers.response`. This namespace only owns the
   Ring handler entrypoint."
  (:require
   [taia.ds.datastar.adapters.handlers.observer :as observer]
   [taia.ds.datastar.adapters.flow :as flow]
   [taia.ds.datastar.adapters.handlers.response :as response :refer [->html error-response render-response]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn- default-component-handler
  "Default handler for component events.
   Discovers component, processes event through state machine,
   renders component, and returns an HTTP response. Timed as a :nds
   observation (`taia.ds.datastar.adapters.handlers.observer`); the 400 below is its
   :error outcome."
  [req component-name component-id event-name controller-name]
  (observer/observe-event!
   :nds component-name controller-name event-name
   (fn []
     (try
       (-> (flow/process-component-event req component-name component-id event-name controller-name)
           render-response)
       (catch Exception e
         (error-response e component-name component-id event-name controller-name))))))

(tests
 "default-component-handler: happy path — pipeline runs, render-response
  produces the Ring response (here we stub pipeline to return plain hiccup
  so render-response goes through ->html, which we also stub)"
 (with-redefs [flow/process-component-event
               (spy/spy (fn [_req _cn _cid _en _ctrl] {:html [:div "ok"]}))
               ->html (fn [hiccup] {:status 200 :body hiccup})]
   (let [resp (default-component-handler {:r 1} :counter "c1" "INCREMENT" nil)]
     [resp
      (spy/calls flow/process-component-event)]))
 := [{:status 200 :body [:div "ok"]}
     [[{:r 1} :counter "c1" "INCREMENT" nil]]]

 "default-component-handler: pipeline throws → 400 response with diagnostic
  body that names the component, component-id, event, and controller"
 (with-redefs [flow/process-component-event
               (fn [& _] (throw (ex-info "boom" {:k :v})))]
   (let [resp (default-component-handler {} :counter "c1" "INCREMENT" "ctrl")]
     [(:status resp)
      (get-in resp [:headers "Content-Type"])
      (and (string? (:body resp))
           (every? #(clojure.string/includes? (:body resp) %)
                   ["boom" ":counter" "c1" "INCREMENT" "ctrl"]))]))
 := [400 "text/plain; charset=utf-8" true]

 "default-component-handler: thrown without a controller-name → body omits
  the 'Controller:' line"
 (with-redefs [flow/process-component-event
               (fn [& _] (throw (ex-info "boom" {})))]
   (-> (default-component-handler {} :c "1" "E" nil)
       :body
       (clojure.string/includes? "Controller:")))
 := false)

(defn default-handler
  "Main handler for dynamic component events.
   Supports two route patterns:
   - /:component/:component-id/:event (backward compatible)
   - /:component/:component-id/:controller/:event (with optional controller)
   
   Example: /ds/user-form/abc123/save
   - component: user-form
   - component-id: abc123
   - event: save
   
   Example with controller: /ds/counter/c1/counter-controller/increment
   - component: counter
   - component-id: c1
   - controller: counter-controller
   - event: increment
   
   Supports both synchronous (1-arity) and asynchronous (3-arity) handlers.
   Async version: [req respond raise]"
  ([req]
   (let [component-name (get-in req [:path-params :component])
         component-id (get-in req [:path-params :component-id])
         controller-name (get-in req [:path-params :controller])
         event-name (get-in req [:path-params :event])]
     (cond
       (not component-name)
       {:status 400 :body "Missing component name"}

       (not component-id)
       {:status 400 :body "Missing component-id"}

       (not event-name)
       {:status 400 :body "Missing event name"}

       :else
       (default-component-handler req
                                  (keyword component-name)
                                  component-id
                                  event-name
                                  controller-name))))
  ([req respond raise]
   (try
     (let [component-name (get-in req [:path-params :component])
           component-id (get-in req [:path-params :component-id])
           controller-name (get-in req [:path-params :controller])
           event-name (get-in req [:path-params :event])]
       (cond
         (not component-name)
         (respond {:status 400 :body "Missing component name"})

         (not component-id)
         (respond {:status 400 :body "Missing component-id"})

         (not event-name)
         (respond {:status 400 :body "Missing event name"})

         :else
         (try
           (let [response (default-component-handler req
                                                     (keyword component-name)
                                                     component-id
                                                     event-name
                                                     controller-name)]
             (respond response))
           (catch Exception e
             (raise e)))))
     (catch Exception e
       (raise e)))))

(tests
 "default-handler 1-arity: missing :component → 400"
 (default-handler {:path-params {:component-id "c1" :event "E"}})
 := {:status 400 :body "Missing component name"}

 "default-handler 1-arity: missing :component-id → 400"
 (default-handler {:path-params {:component "counter" :event "E"}})
 := {:status 400 :body "Missing component-id"}

 "default-handler 1-arity: missing :event → 400"
 (default-handler {:path-params {:component "counter" :component-id "c1"}})
 := {:status 400 :body "Missing event name"}

 "default-handler 1-arity: all path-params present → forwards to
  default-component-handler with component coerced to keyword and the
  controller path-param threaded through"
 (with-redefs [flow/process-component-event
               (spy/spy (fn [_req _cn _cid _en _ctrl] {:html [:div "ok"]}))
               ->html (fn [_] {:status 200 :body :rendered})]
   (let [resp (default-handler {:path-params {:component       "counter"
                                              :component-id    "c1"
                                              :controller      "counter-ctrl"
                                              :event           "INCREMENT"}})]
     [resp (spy/calls flow/process-component-event)]))
 := [{:status 200 :body :rendered}
     [[{:path-params {:component "counter" :component-id "c1"
                      :controller "counter-ctrl" :event "INCREMENT"}}
       :counter "c1" "INCREMENT" "counter-ctrl"]]]

 "default-handler 3-arity: missing path-param → respond called with 400"
 (let [respond (spy/spy)]
   (default-handler {:path-params {}} respond (fn [_] (throw (ex-info "raise" {}))))
   (spy/calls respond)) := [[{:status 400 :body "Missing component name"}]]

 "default-handler 3-arity: happy path → respond called with rendered response"
 (with-redefs [flow/process-component-event
               (fn [& _] {:html [:div "ok"]})
               ->html (fn [_] {:status 200 :body :rendered})]
   (let [respond (spy/spy)]
     (default-handler {:path-params {:component "counter"
                                     :component-id "c1"
                                     :event "INCREMENT"}}
                      respond
                      (fn [_] (throw (ex-info "raise" {}))))
     (spy/calls respond))) := [[{:status 200 :body :rendered}]]

 "default-handler 3-arity: pipeline throws inside default-component-handler
  → caught by inner try/catch and turned into a 400 (raise NOT called).
  This is intentional: inner try makes the 3-arity behave like the 1-arity
  for component errors so the client sees the diagnostic body."
 (with-redefs [flow/process-component-event
               (fn [& _] (throw (ex-info "boom" {})))]
   (let [respond (spy/spy)
         raise   (spy/spy)]
     (default-handler {:path-params {:component "c" :component-id "1" :event "E"}} respond raise)
     (spy/calls respond) := [[{:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"} :body _}]]
     (clojure.string/includes? (:body (ffirst (spy/calls respond))) "boom") := true
     (spy/not-called? raise) := true)))

