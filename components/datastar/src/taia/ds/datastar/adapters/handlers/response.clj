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
