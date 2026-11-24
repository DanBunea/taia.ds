(ns taia.ds.datastar.adapters.handlers.response
  (:require
   [charred.api :as charred]
   [dev.onionpancakes.chassis.compiler :as hc]
   [dev.onionpancakes.chassis.core :as h]
   [ring.util.response :as ruresp]
   [starfederation.datastar.clojure.adapter.ring :refer [->sse-response on-open]]
   [starfederation.datastar.clojure.api :as d*]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn ->html [hiccup]
  (-> hiccup
      h/html
      hc/compile
      ruresp/response
      (ruresp/content-type "text/html; charset=UTF-8")))

(defn- resolve-d* [x]
  (if (and (keyword? x) (= "d*" (namespace x)))
    @(ns-resolve 'starfederation.datastar.clojure.api (symbol (name x)))
    x))

(defn to-d* [m]
  (into {}
        (map (fn [[k v]]
               [(resolve-d* k) (resolve-d* v)]))
        m))

(defn send-effect!
  "Dispatches a single effect to the SSE generator.
   Handles :patch (DOM elements), :patch-signals (client signals),
   and :execute-script (run JS on client)."
  [sse-gen [effect-type payload opts]]
  (case effect-type
    :patch
    (d*/patch-elements! sse-gen
                        (h/html (hc/compile payload))
                        (to-d* opts))
    :patch-signals
    (d*/patch-signals! sse-gen
                       (charred/write-json-str payload)
                       (to-d* opts))
    :execute-script
    (d*/execute-script! sse-gen
                        payload
                        (to-d* opts))))

(def ^:private mock-sse-gen ::sse-gen)

(tests
 ;; :patch calls patch-elements! with compiled HTML and resolved opts
 (with-redefs [d*/patch-elements! (spy/spy (fn [& _] true))]
   (send-effect! mock-sse-gen [:patch [:div#foo "hello"] {}])
   (spy/calls d*/patch-elements!) := [[mock-sse-gen "<div id=\"foo\">hello</div>" {}]])

 ;; :patch-signals calls patch-signals! with a JSON string; read back to compare as map
 (with-redefs [d*/patch-signals! (spy/spy (fn [& _] true))]
   (send-effect! mock-sse-gen [:patch-signals {:counter 0} {}])
   (spy/called-once? d*/patch-signals!) := true
   (let [[_gen json _opts] (first (spy/calls d*/patch-signals!))]
     (charred/read-json json :key-fn keyword)) := {:counter 0})

 ;; :execute-script calls execute-script! with the script text
 (with-redefs [d*/execute-script! (spy/spy (fn [& _] true))]
   (send-effect! mock-sse-gen [:execute-script "console.log('hi')" {}])
   (spy/calls d*/execute-script!) := [[mock-sse-gen "console.log('hi')" {}]])

 ;; unknown effect type throws
 (try
   (send-effect! mock-sse-gen [:unknown [:div] {}])
   :no-throw
   (catch IllegalArgumentException _ :thrown)) := :thrown

 ;; only :patch, :patch-signals, :execute-script entries pass the filter
 (mapv first
       (filter #(#{:patch :patch-signals :execute-script} (first %))
               [[:broadcast-elements! [:div] {}]
                [:patch [:span#a "x"] {}]
                [:broadcast-hide-loading "chat-1"]
                [:patch-signals {:counter 1} {}]
                [:execute-script "alert(1)" {}]]))
 := [:patch :patch-signals :execute-script])

(defn send-responses
  "Sends all [:patch ...], [:patch-signals ...], and [:execute-script ...] effects
   from `effects` as a one-shot SSE response. Multiple events are emitted before
   the SSE connection is closed. Other effects (e.g. :broadcast-elements!) are
   ignored here — they are handled separately by broadcast-to-connections."
  [req effects]
  (let [patch-effects (filter #(#{:patch :patch-signals :execute-script} (first %)) effects)]
    (->sse-response req
                    {on-open (fn [sse-gen]
                               (d*/with-open-sse sse-gen
                                 (doseq [effect patch-effects]
                                   (send-effect! sse-gen effect))))})))

(defn component-responses?
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

(defn has-patch-effects?
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

(defn render-response
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

(defn error-response
  "The 400 a component event answers with when a step threw (or timed out):
   a plain-text diagnostic naming the component, instance, event and
   controller."
  [^Throwable e component-name component-id event-name controller-name]
  {:status 400
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "Error processing component event: " (.getMessage e) "\n"
              "Component: " component-name "\n"
              "Component-ID: " component-id "\n"
              "Event: " event-name "\n"
              (when controller-name (str "Controller: " controller-name "\n"))
              "Error message: " (.getMessage e) "\n"
              "Stack trace: " (with-out-str
                                (.printStackTrace e (java.io.PrintWriter. *out*))))})
