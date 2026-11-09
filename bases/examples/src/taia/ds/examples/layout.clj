(ns taia.ds.examples.layout
  "The examples registry and the page shell every example is served in.

   The shell is ordinary hiccup rendered by the base — not a Datastar
   component. Each example page mounts ONE component: a `div` whose
   `data-init` fires the component's INIT, either a one-shot `@post` to
   /ds/nds/… or, for connected components, an `@get` to /ds/nds-connect/…
   that keeps the SSE stream open."
  (:require
   [clojure.string :as str]))

(def examples
  "Ordered progression. `:component` is `<group>.<name>` under the search path
   `taia.ds.examples.components`; `:cid` is the component-id (also the DOM id
   and the signal namespace), kept free of dashes so signal paths stay simple."
  [{:slug "counter" :title "1 · Counter" :component "basics.counter" :cid "counter1"
    :summary "One-shot events, `:memory` round-trip through the signal store, the render fn as the single source of markup."
    :doc "01-counter.md"}
   {:slug "live-counter" :title "2 · Live counter" :component "live.live-counter" :cid "live1" :connected? true
    :summary "A connected component: an SSE stream per tab, server-owned state, `send-ui!` fan-out to everyone. Open it twice with `?user=alice` and `?user=bob`."
    :doc "02-live-counter.md"}
   {:slug "signup" :title "3 · Signup form" :component "signup.signup" :cid "signup1"
    :summary "Client-owned form signals with `data-bind`, guarded transitions (`:cond`), an effects list with `:execute-script`."
    :doc "03-signup-form.md"}
   {:slug "wizard" :title "4 · Wizard" :component "wizard.wizard" :cid "wizard1"
    :summary "Hierarchical states, a machine-wide RESET, an invoke with `:on-done` / `:on-error`."
    :doc "04-wizard.md"}
   {:slug "todomvc" :title "5 · TodoMVC" :component "todomvc.todomvc" :cid "todos1"
    :summary "The classic, entirely in `:memory`: query-string event params, filters, inline editing, keyboard events."
    :doc "05-todomvc.md"}
   {:slug "weather" :title "6 · Weather" :component "weather.weather" :cid "weather1"
    :summary "An invoke calling a public API (Open-Meteo), a lazy-loading skeleton, `data-indicator` while a request is in flight, the error path."
    :doc "06-weather.md"}])

(defn find-example [slug]
  (first (filter #(= slug (:slug %)) examples)))

(def ^:private css
  "body{font-family:system-ui,sans-serif;margin:0;background:#f6f7f9;color:#1d2330}
   header{background:#1d2330;color:#fff;padding:.75rem 1.25rem}header a{color:#fff;text-decoration:none;font-weight:600}
   main{max-width:720px;margin:1.5rem auto;padding:0 1rem}
   .card{background:#fff;border-radius:12px;padding:1.25rem 1.5rem;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:1rem}
   .muted{color:#6b7280;font-size:.9rem}.big{font-size:2.5rem;font-weight:700;margin:.5rem 0}
   .row{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center}
   button{border:0;border-radius:8px;padding:.5rem 1rem;background:#2563eb;color:#fff;font-size:1rem;cursor:pointer}
   button.secondary{background:#e5e7eb;color:#1d2330}button:disabled{opacity:.5}
   input[type=text],input[type=email]{border:1px solid #d1d5db;border-radius:8px;padding:.5rem .75rem;font-size:1rem;width:100%;box-sizing:border-box}
   label{display:block;margin:.75rem 0 .25rem;font-weight:600}.error{color:#b91c1c;font-size:.9rem}.ok{color:#15803d}
   .steps{display:flex;gap:.5rem;margin-bottom:1rem}.steps span{padding:.25rem .75rem;border-radius:999px;background:#e5e7eb}.steps span.active{background:#2563eb;color:#fff}
   ul.todos{list-style:none;padding:0;margin:0}ul.todos li{display:flex;gap:.5rem;align-items:center;padding:.5rem 0;border-bottom:1px solid #eee}
   ul.todos li.completed .text{text-decoration:line-through;color:#9ca3af}ul.todos li .text{flex:1;cursor:pointer}
   .filters a{margin-right:.5rem;cursor:pointer;color:#2563eb}.filters a.selected{font-weight:700;text-decoration:underline}
   pre{background:#f3f4f6;padding:.75rem;border-radius:8px;overflow:auto;font-size:.85rem}
   details.inspect{margin-top:1rem;border-top:1px dashed #d1d5db;padding-top:.5rem}details.inspect summary{cursor:pointer;font-size:.8rem}details.inspect pre{margin:.5rem 0 0;font-size:.8rem}
   .index li{margin:.75rem 0}.index a{font-weight:600}")

(defn page
  "Full-page hiccup: loads datastar.js (served from the library's resources via
   `wrap-resource \"datastar\"`) and the reconnect watchdog."
  [title & body]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title (str title " · taia.ds examples")]
    [:style css]
    [:script {:type "module" :src "/js/datastar.js"}]
    [:script {:src "/js/sse-reconnect.js" :defer true}]]
   [:body
    [:header [:a {:href "/"} "taia.ds examples"]]
    (into [:main] body)]])

(defn- init-url [{:keys [component cid connected?]}]
  (str (if connected? "/ds/nds-connect/" "/ds/nds/") component "/" cid "/init"))

(defn mount
  "The element an example is mounted in. `user` becomes the top-level `$user`
   signal so every request can carry it as `?user=` (see
   `taia.ds.examples.core/wrap-logged-user`)."
  [{:keys [cid connected?] :as example} user]
  [:div {:data-signals (str "{user:'" user "'}")}
   [:div {:id cid
          "data-init" (str (if connected? "@get('" "@post('") (init-url example) "?user='+$user)")}
    [:p.muted "loading…"]]])

(defn index []
  (page "Examples"
        [:h1 "Examples"]
        [:p.muted "A progression through the framework. Each page mounts one component; the source of every example lives under "
         [:code "bases/examples/src/taia/ds/examples/components/"] "."]
        (into [:ol.index]
              (for [{:keys [slug title summary]} examples]
                [:li [:a {:href (str "/examples/" slug)} title] [:br] [:span.muted summary]]))))

(defn example-page [{:keys [title summary component doc] :as example} user]
  (page title
        [:h1 title]
        [:p.muted summary]
        (mount example user)
        [:p.muted "Component " [:code component] " · signed in as " [:code user]
         (when doc [:span " · " [:a {:href (str "https://github.com/DanBunea/taia.ds/blob/main/docs/examples/" doc)} "read the walkthrough"]])]))

(defn not-found [slug]
  (page "Not found" [:h1 "No such example"] [:p.muted (str "'" slug "' is not one of: " (str/join ", " (map :slug examples)))]))
