(ns taia.ds.examples.components.live.countdown
  "Example 7 — Countdown (a connected component driven by server events).

   The page mounts this component with `@get('/ds/nds-connect/…/init')`: the
   stream opens and so does the component's FLOW, whose controller holds the
   ctx between events. Start is a `@post` to the SAME nds-connect path — it
   runs through that flow and is answered with the render. After that the
   server drives it: a TICK a second, sent with `send-event!`, each one
   rendered and pushed down the stream.

   No `data-signals`: `:remaining` is server-owned, held by the flow."
  (:require
   [taia.ds.examples.inspect :as inspect]))

(defn- post [cid event]
  ;; `$user` is the page-level signal the home page seeds; every request carries it
  ;; as ?user= so the examples app can tell the tabs apart.
  (str "@post('/ds/nds-connect/live.countdown/" cid "/" event "?user='+$user)"))

(defn countdown [{:keys [component-id state remaining] :as ctx}]
  [:div.card {:id component-id}
   [:h2 "Countdown"]
   [:p.big {:data-testid "remaining"} (str (or remaining 0))]
   [:p.muted {:data-testid "phase"}
    (case state
      :running "Counting down — every tick is a server event pushed down this tab's stream."
      :done    "Done. No click since Start: the server sent every tick."
      "Press Start: the server takes it from there.")]
   [:div.row
    [:button {:type "button" :data-testid "start" :disabled (= :running state)
              "data-on:click" (post component-id "start")}
     (if (= :done state) "Again" "Start")]]
   (inspect/state-pre ctx)])
