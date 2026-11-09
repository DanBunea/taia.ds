(ns taia.ds.examples.components.live.live-counter
  "Example 2 — Live counter (a connected component).

   The page mounts this component with `@get('/ds/nds-connect/…/init')`, so
   the INIT render arrives over an SSE stream that then STAYS OPEN. The
   server can now push into this tab at any time with `send-ui!`.

   The count is server-owned (an atom in the controller) and there is no
   `data-signals` here: nothing round-trips because nothing is the client's
   to own. Each click is an ordinary one-shot POST whose action bumps the
   atom and broadcasts the new render to every tab connected under this
   component-id — open the page twice as `?user=alice` and `?user=bob`.

   Keep the fragment user-neutral: the same markup lands in everyone's tab."
  (:require
   [clojure.string :as str]))

(defn- post [cid event]
  ;; `$user` is the page-level signal the shell seeds; every request carries it
  ;; as ?user= so the examples app can tell the tabs apart.
  (str "@post('/ds/nds/live.live-counter/" cid "/" event "?user='+$user)"))

(defn live-counter [{:keys [component-id count]}]
  [:div.card {:id component-id}
   [:h2 "Live counter"]
   [:p.big {:data-testid "count"} (str (or count 0))]
   [:div.row
    [:button {:type "button" :data-testid "inc" "data-on:click" (post component-id "increment")} "+1 for everyone"]
    [:button.secondary {:type "button" "data-on:click" (post component-id "reset")} "Reset"]]
   [:p.muted (str/join " " ["The number is an atom on the server." "Every tab connected to this component-id sees each change at once,"
                            "pushed through its own SSE stream." "Open this page in a second tab with a different ?user= to watch it."])]])
