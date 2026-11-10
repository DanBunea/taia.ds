(ns taia.ds.datastar.adapters.handlers.default-component-handler
  "Dynamic endpoint handler for component events.
   Handles routes like /:component/:component-id/:event.

   Pipeline functions (assoc-component-fn-and-machine, assoc-event,
   assoc-initial-ctx-and-state, assoc-process-event-results, assoc-html,
   process-component-event) and their tests live in
   `taia.ds.datastar.adapters.handlers.pipeline` — this namespace only owns the response-
   shaping logic and the Ring handler entrypoint."
  (:require
   [taia.ds.datastar.adapters.handlers.observer :as observer]
   [taia.ds.datastar.adapters.handlers.pipeline :as pipeline]
   [taia.ds.datastar.adapters.handlers.response :as response :refer [->html send-responses]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn- component-responses?
  "Returns true if the component returned a list of effects rather than plain hiccup.
   Effects are a seq of vectors whose first element is a keyword: [[:effect-name ...] ...]."
  [html]
  (and (vector? html) (vector? (first html)) (keyword? (first (first html)))))

(tests
 "component-responses?: plain hiccup → false (first element is the tag keyword,
  not a nested vector)"
 (component-responses? [:div "x"])              := false
 (component-responses? [:div {:class "y"} "x"]) := false

 "component-responses?: a list of effect-vectors keyed by keyword → true"
 (component-responses? [[:patch [:div]]])               := true
 (component-responses? [[:execute-script "alert(1)"]])  := true
 (component-responses? [[:patch [:div]]
                        [:patch-signals {:k 1}]])       := true

 "component-responses?: empty / nil / non-vector → falsy"
 (component-responses? [])  := false
 (component-responses? nil) := false)

(defn- has-patch-effects?
  "Returns true if the effects list contains at least one :patch, :patch-signals, or :execute-script effect."
  [html]
  (and (component-responses? html)
       (some #(#{:patch :patch-signals :execute-script} (first %)) html)))

(tests
 "has-patch-effects?: an effects list with at least one :patch / :patch-signals
  / :execute-script → truthy"
 (boolean (has-patch-effects? [[:patch [:div]]]))                 := true
 (boolean (has-patch-effects? [[:patch-signals {:k 1}]]))         := true
 (boolean (has-patch-effects? [[:execute-script "x"]]))           := true
 (boolean (has-patch-effects? [[:broadcast-elements [:div]]
                               [:patch [:div]]]))                 := true

 "has-patch-effects?: effects list with only non-patch effect types → falsy"
 (boolean (has-patch-effects? [[:broadcast-elements [:div]]]))    := false
 (boolean (has-patch-effects? [[:broadcast-hide-loading "c1"]]))  := false

 "has-patch-effects?: plain hiccup / empty / nil → falsy"
 (boolean (has-patch-effects? [:div "x"])) := false
 (boolean (has-patch-effects? []))         := false
 (boolean (has-patch-effects? nil))        := false)

(defn- render-response
  "Final pipeline step: converts the component result to a Ring response.
   - Effects with :patch/:patch-signals → one-shot SSE (multiple DOM/signal patches)
   - Effects without :patch             → empty HTML body (no-op)
   - Plain hiccup                       → rendered as HTML"
  [{:keys [html request]}]
  (cond
    (has-patch-effects? html)   (send-responses request html)
    (component-responses? html) (->html [])
    :else                       (->html html)))

(tests
 "render-response: html with patch effects → delegates to send-responses
  with the original request and the effects list (no ->html call)"
 (with-redefs [send-responses (spy/spy (fn [_req _html] ::sse-response))
               ->html         (spy/spy (fn [_html] ::http-response))]
   (let [out (render-response {:request {::r 1}
                               :html    [[:patch [:div "x"]]]})]
     [out
      (spy/calls send-responses)
      (spy/not-called? ->html)])) := [::sse-response
                                      [[{::r 1} [[:patch [:div "x"]]]]]
                                      true]

 "render-response: effects-shaped html with NO patch entries → empty body via
  ->html [] (no SSE)"
 (with-redefs [send-responses (spy/spy (fn [_ _] ::sse-response))
               ->html         (spy/spy (fn [_] ::http-response))]
   (let [out (render-response {:request {}
                               :html    [[:broadcast-elements [:div]]]})]
     [out
      (spy/not-called? send-responses)
      (spy/calls ->html)])) := [::http-response true [[[]]]]

 "render-response: plain hiccup → ->html with the hiccup verbatim"
 (with-redefs [send-responses (spy/spy (fn [_ _] ::sse-response))
               ->html         (spy/spy (fn [_] ::http-response))]
   (let [out (render-response {:request {}
                               :html    [:div "x"]})]
     [out (spy/calls ->html)])) := [::http-response [[[:div "x"]]]])

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
       (-> (pipeline/process-component-event req component-name component-id event-name controller-name)
           render-response)
       (catch Exception e
         {:status 400
          :headers {"Content-Type" "text/plain; charset=utf-8"}
          :body (str "Error processing component event: " (.getMessage e) "\n"
                     "Component: " component-name "\n"
                     "Component-ID: " component-id "\n"
                     "Event: " event-name "\n"
                     (when controller-name (str "Controller: " controller-name "\n"))
                     "Error message: " (.getMessage e) "\n"
                     "Stack trace: " (with-out-str
                                       (.printStackTrace e (java.io.PrintWriter. *out*))))})))))

(tests
 "default-component-handler: happy path — pipeline runs, render-response
  produces the Ring response (here we stub pipeline to return plain hiccup
  so render-response goes through ->html, which we also stub)"
 (with-redefs [pipeline/process-component-event
               (spy/spy (fn [_req _cn _cid _en _ctrl] {:html [:div "ok"]}))
               ->html (fn [hiccup] {:status 200 :body hiccup})]
   (let [resp (default-component-handler {:r 1} :counter "c1" "INCREMENT" nil)]
     [resp
      (spy/calls pipeline/process-component-event)]))
 := [{:status 200 :body [:div "ok"]}
     [[{:r 1} :counter "c1" "INCREMENT" nil]]]

 "default-component-handler: pipeline throws → 400 response with diagnostic
  body that names the component, component-id, event, and controller"
 (with-redefs [pipeline/process-component-event
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
 (with-redefs [pipeline/process-component-event
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
 (with-redefs [pipeline/process-component-event
               (spy/spy (fn [_req _cn _cid _en _ctrl] {:html [:div "ok"]}))
               ->html (fn [_] {:status 200 :body :rendered})]
   (let [resp (default-handler {:path-params {:component       "counter"
                                              :component-id    "c1"
                                              :controller      "counter-ctrl"
                                              :event           "INCREMENT"}})]
     [resp (spy/calls pipeline/process-component-event)]))
 := [{:status 200 :body :rendered}
     [[{:path-params {:component "counter" :component-id "c1"
                      :controller "counter-ctrl" :event "INCREMENT"}}
       :counter "c1" "INCREMENT" "counter-ctrl"]]]

 "default-handler 3-arity: missing path-param → respond called with 400"
 (let [respond (spy/spy)]
   (default-handler {:path-params {}} respond (fn [_] (throw (ex-info "raise" {}))))
   (spy/calls respond)) := [[{:status 400 :body "Missing component name"}]]

 "default-handler 3-arity: happy path → respond called with rendered response"
 (with-redefs [pipeline/process-component-event
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
 (with-redefs [pipeline/process-component-event
               (fn [& _] (throw (ex-info "boom" {})))]
   (let [respond (spy/spy)
         raise   (spy/spy)]
     (default-handler {:path-params {:component "c" :component-id "1" :event "E"}} respond raise)
     (spy/calls respond) := [[{:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"} :body _}]]
     (clojure.string/includes? (:body (ffirst (spy/calls respond))) "boom") := true
     (spy/not-called? raise) := true)))

