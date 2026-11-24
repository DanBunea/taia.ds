(ns {{top/ns}}.{{main/ns}}.web.components.basics.counter
  "Counter.

   The smallest complete component. Three things to notice:

   * The render fn is named after the last namespace segment (`counter`) and
     is the ONLY place markup lives: every event re-renders it and Datastar
     morphs the result into the element with the same id.
   * `:memory` is the component's state. It is serialized into `data-signals`
     on the root element, sent back with every `@post`, and handed to the
     state machine as `(:memory ctx)` — that is the whole persistence story
     of a one-shot component. Keep it JSON-shaped (numbers, strings, booleans,
     vectors, maps of those).
   * Events are `POST /ds/nds/<group>.<name>/<component-id>/<event>`; the
     event name is upper-cased into the machine's keyword (`increment` →
     `:INCREMENT`)."
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]
   [taia.ds.datastar.interface :as ds]
   [{{top/ns}}.{{main/ns}}.web.inspect :as inspect]))

(defn- post [cid event]
  (str "@post('/ds/nds/basics.counter/" cid "/" event "')"))

(defn counter [{:keys [component-id memory] :as ctx}]
  [:div.card {:id component-id
              :data-signals (ds/ctx->data-signals ctx)}
   [:h2 "Counter"]
   [:p.big {:data-testid "count"} (str (:count memory 0))]
   [:div.row
    [:button {:type "button" :data-testid "dec" "data-on:click" (post component-id "decrement")} "−"]
    [:button {:type "button" :data-testid "inc" "data-on:click" (post component-id "increment")} "+"]
    [:button.secondary {:type "button" "data-on:click" (post component-id "reset")} "Reset"]]
   [:p.muted "The count lives in " [:code ":memory"] ", round-tripped through the "
    [:code "data-signals"] " attribute above. Reload the page: it is gone — one-shot components own no server state."]
   (inspect/state-pre ctx)])

;;; the render. Its collaborators are spied (they have tests of their own):
;;; the data-signals string and the state viewer.

(tests
 "the card: the count, three buttons posting to this component's own routes,
  the signals and the state viewer from their fns, called with the ctx"
 (with-redefs [ds/ctx->data-signals (spy/stub "SIGNALS")
               inspect/state-pre    (spy/stub [:details.inspect])]
   (counter {:component-id "counter1" :state :displayed :memory {:count 2}})
   := [:div.card {:id "counter1" :data-signals "SIGNALS"}
       [:h2 "Counter"]
       [:p.big {:data-testid "count"} "2"]
       [:div.row
        [:button {:type "button" :data-testid "dec" "data-on:click" "@post('/ds/nds/basics.counter/counter1/decrement')"} "−"]
        [:button {:type "button" :data-testid "inc" "data-on:click" "@post('/ds/nds/basics.counter/counter1/increment')"} "+"]
        [:button.secondary {:type "button" "data-on:click" "@post('/ds/nds/basics.counter/counter1/reset')"} "Reset"]]
       _
       [:details.inspect]]
   (spy/called-once-with? ds/ctx->data-signals {:component-id "counter1" :state :displayed :memory {:count 2}}) := true
   (spy/called-once-with? inspect/state-pre {:component-id "counter1" :state :displayed :memory {:count 2}}) := true)

 "no count in memory yet: shows 0"
 (with-redefs [ds/ctx->data-signals (spy/stub "SIGNALS")
               inspect/state-pre    (spy/stub [:details.inspect])]
   (counter {:component-id "counter1" :state :displayed :memory {}})
   := [:div.card _ _ [:p.big {:data-testid "count"} "0"] _ _ _]))
