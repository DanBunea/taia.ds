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

(tests
 "->html: hiccup → a 200 Ring response with the rendered HTML"
 (->html [:div#foo "x"])
 := {:status 200 :headers {"Content-Type" "text/html; charset=UTF-8"} :body "<div id=\"foo\">x</div>"}

 "->html: the empty hiccup (an effects list with nothing to patch) → an empty body"
 (->html [])
 := {:status 200 :headers {"Content-Type" "text/html; charset=UTF-8"} :body ""})

(defn- resolve-d* [x]
  (if (and (keyword? x) (= "d*" (namespace x)))
    @(ns-resolve 'starfederation.datastar.clojure.api (symbol (name x)))
    x))

(defn to-d* [m]
  (into {}
        (map (fn [[k v]]
               [(resolve-d* k) (resolve-d* v)]))
        m))

(tests
 "to-d*: :d*/<name> keys and values resolve to the Datastar SDK's option
  constants; anything else is kept as it is"
 (to-d* {:d*/selector "#x" :d*/patch-mode :d*/patch-mode :foo 1})
 := {d*/selector "#x" d*/patch-mode d*/patch-mode :foo 1}

 "to-d*: nil / no opts → {}"
 (to-d* nil) := {})

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

(tests
 ":patch → patch-elements! with the hiccup compiled to HTML and the opts resolved"
 (with-redefs [d*/patch-elements! (spy/spy (constantly true))]
   (send-effect! ::sse-gen [:patch [:div#foo "hello"] {:d*/selector "#foo"}])
   (spy/called-once-with? d*/patch-elements! ::sse-gen "<div id=\"foo\">hello</div>" {d*/selector "#foo"}) := true)

 ":patch-signals → patch-signals! with the signals as a JSON string"
 (with-redefs [d*/patch-signals! (spy/spy (constantly true))]
   (send-effect! ::sse-gen [:patch-signals {:counter 0} {}])
   (spy/called-once-with? d*/patch-signals! ::sse-gen "{\"counter\":0}" {}) := true)

 ":execute-script → execute-script! with the script text"
 (with-redefs [d*/execute-script! (spy/spy (constantly true))]
   (send-effect! ::sse-gen [:execute-script "console.log('hi')" {}])
   (spy/called-once-with? d*/execute-script! ::sse-gen "console.log('hi')" {}) := true)

 "an unknown effect type throws"
 (send-effect! ::sse-gen [:unknown [:div] {}]) :throws IllegalArgumentException)

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

(tests
 (defn- on-open-fn
   "The on-open callback send-responses handed ->sse-response."
   [sse-response-spy]
   (-> sse-response-spy
       spy/first-call
       second
       (get on-open)))

 "send-responses: a one-shot SSE response for the request"
 (with-redefs [->sse-response (spy/spy (constantly ::sse-response))]
   (send-responses {::r 1}
                   [[:broadcast-elements! [:div] {}]
                    [:patch [:span#a "x"] {}]
                    [:broadcast-hide-loading "chat-1"]
                    [:patch-signals {:counter 1} {}]
                    [:execute-script "alert(1)" {}]])
   := ::sse-response
   (-> ->sse-response
       spy/first-call
       first)
   := {::r 1})

 "send-responses: once the stream opens, only the :patch / :patch-signals /
  :execute-script effects are sent, in order (the rest are broadcast
  elsewhere), and the stream is closed"
 (with-redefs [->sse-response (spy/spy (constantly ::sse-response))
               send-effect!   (spy/spy)
               d*/close-sse!  (spy/spy)]
   (send-responses {::r 1}
                   [[:broadcast-elements! [:div] {}]
                    [:patch [:span#a "x"] {}]
                    [:broadcast-hide-loading "chat-1"]
                    [:patch-signals {:counter 1} {}]
                    [:execute-script "alert(1)" {}]])
   ((on-open-fn ->sse-response) ::sse-gen)
   (spy/calls send-effect!)
   := [[::sse-gen [:patch [:span#a "x"] {}]]
       [::sse-gen [:patch-signals {:counter 1} {}]]
       [::sse-gen [:execute-script "alert(1)" {}]]]
   (spy/called-once-with? d*/close-sse! ::sse-gen) := true))

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
 "render-response: html with patch effects → send-responses with the request
  and the effects list (no ->html)"
 (with-redefs [send-responses (spy/spy (constantly ::sse-response))
               ->html         (spy/spy (constantly ::http-response))]
   (render-response {:request {::r 1} :html [[:patch [:div "x"]]]}) := ::sse-response
   (spy/called-once-with? send-responses {::r 1} [[:patch [:div "x"]]]) := true
   (spy/not-called? ->html) := true)

 "render-response: effects-shaped html with NO patch entries → an empty body
  via ->html [] (no SSE)"
 (with-redefs [send-responses (spy/spy (constantly ::sse-response))
               ->html         (spy/spy (constantly ::http-response))]
   (render-response {:request {} :html [[:broadcast-elements [:div]]]}) := ::http-response
   (spy/called-once-with? ->html []) := true
   (spy/not-called? send-responses) := true)

 "render-response: plain hiccup → ->html with the hiccup verbatim"
 (with-redefs [send-responses (spy/spy (constantly ::sse-response))
               ->html         (spy/spy (constantly ::http-response))]
   (render-response {:request {} :html [:div "x"]}) := ::http-response
   (spy/called-once-with? ->html [:div "x"]) := true
   (spy/not-called? send-responses) := true))

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

(tests
 (defn- without-stack-trace
   "The response with its body cut before the (machine-specific) stack trace."
   [resp]
   (update resp :body #(first (clojure.string/split % #"Stack trace: "))))

 "error-response: a plain-text 400 naming the error, component, instance,
  event and controller"
 (-> (ex-info "boom" {:k :v})
     (error-response :counter "c1" "INCREMENT" "ctrl")
     without-stack-trace)
 := {:status  400
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body    (str "Error processing component event: boom\n"
                   "Component: :counter\n"
                   "Component-ID: c1\n"
                   "Event: INCREMENT\n"
                   "Controller: ctrl\n"
                   "Error message: boom\n")}

 "error-response: no controller-name → no 'Controller:' line"
 (-> (ex-info "boom" {:k :v})
     (error-response :c "1" "E" nil)
     without-stack-trace
     :body)
 := (str "Error processing component event: boom\n"
         "Component: :c\n"
         "Component-ID: 1\n"
         "Event: E\n"
         "Error message: boom\n")

 "error-response: the body ends with the exception's stack trace"
 (-> (ex-info "boom" {:k :v})
     (error-response :c "1" "E" nil)
     :body
     (clojure.string/includes? "Stack trace: clojure.lang.ExceptionInfo: boom {:k :v}"))
 := true)
