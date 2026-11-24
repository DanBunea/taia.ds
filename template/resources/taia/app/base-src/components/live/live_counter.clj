(ns {{top/ns}}.{{main/ns}}.web.components.live.live-counter
  "Live counter (a connected component, two ways in).

   The page mounts this component with `@get('/ds/nds-connect/…/init')`, so
   the INIT render arrives over an SSE stream that then STAYS OPEN, with the
   component's flow behind it. Two kinds of event go in through that flow's
   in-chan:

   * a click: a `@post` to the same nds-connect path, carrying the tab's
     signals — the count is the tab's, in `:memory`, round-tripped through
     `data-signals` like the basic counter's;
   * a server event: `send-event!` (from the REPL, a job, an agent), which has no
     signals and so brings the count as its params. Its render comes down the
     open stream, and the render's `data-signals` update the tab's store.

   Nothing is kept on the server between events."
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]
   [taia.ds.datastar.interface :as ds]
   [{{top/ns}}.{{main/ns}}.web.inspect :as inspect]))

(defn- post [cid event]
  ;; The nds-connect path: a click runs through the flow this tab's stream
  ;; opened, the same in-chan `send-event!` uses.
  ;; `$user` is the page-level signal the home page seeds; every request carries it
  ;; as ?user= so the app can tell the tabs apart.
  (str "@post('/ds/nds-connect/live.live-counter/" cid "/" event "?user='+$user)"))

(defn live-counter [{:keys [component-id memory] :as ctx}]
  [:div.card {:id component-id :data-signals (ds/ctx->data-signals ctx)}
   [:h2 "Live counter"]
   [:p.big {:data-testid "count"} (str (:count memory 0))]
   [:div.row
    [:button {:type "button" :data-testid "inc" "data-on:click" (post component-id "increment")} "+1"]
    [:button.secondary {:type "button" :data-testid "reset" "data-on:click" (post component-id "reset")} "Reset"]]
   [:p.muted "The count is this tab's, in " [:code ":memory"] ". A click and a server event ("
    [:code "send-event!"] ") go in through the same in-chan; a server event's render comes down the open stream."]
   (inspect/state-pre ctx)])

;;; the render. Its collaborators are spied (they have tests of their own):
;;; the data-signals string and the state viewer.

(tests
 "the card: the count, two buttons posting through the nds-connect path with
  the page's $user, the signals and the state viewer from their fns, called
  with the ctx"
 (with-redefs [ds/ctx->data-signals (spy/stub "SIGNALS")
               inspect/state-pre    (spy/stub [:details.inspect])]
   (live-counter {:component-id "live1" :state :displayed :memory {:count 3}})
   := [:div.card {:id "live1" :data-signals "SIGNALS"}
       [:h2 "Live counter"]
       [:p.big {:data-testid "count"} "3"]
       [:div.row
        [:button {:type "button" :data-testid "inc" "data-on:click" "@post('/ds/nds-connect/live.live-counter/live1/increment?user='+$user)"} "+1"]
        [:button.secondary {:type "button" :data-testid "reset" "data-on:click" "@post('/ds/nds-connect/live.live-counter/live1/reset?user='+$user)"} "Reset"]]
       _
       [:details.inspect]]
   (spy/called-once-with? ds/ctx->data-signals {:component-id "live1" :state :displayed :memory {:count 3}}) := true
   (spy/called-once-with? inspect/state-pre {:component-id "live1" :state :displayed :memory {:count 3}}) := true)

 "no count in memory yet: shows 0"
 (with-redefs [ds/ctx->data-signals (spy/stub "SIGNALS")
               inspect/state-pre    (spy/stub [:details.inspect])]
   (live-counter {:component-id "live1" :state :displayed :memory {}})
   := [:div.card _ _ [:p.big {:data-testid "count"} "0"] _ _ _]))
