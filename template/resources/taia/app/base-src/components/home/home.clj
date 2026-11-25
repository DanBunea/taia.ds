(ns {{top/ns}}.{{main/ns}}.web.components.home.home
  "The home page as a Datastar component: `<body>` is the component's root
   (id `home`, the page's signals on it), and each page mounts ONE child
   component — a `div` whose `data-init` POSTs the child's INIT, either
   one-shot to /ds/nds/… or, for connected components, to /ds/nds-connect/…,
   which opens the connection (and keeps the SSE stream open).

   Two signal stores on the page: the page's own (`$home.*`, on `<body>`,
   what this component round-trips) and a page-level `$user`, seeded on
   `<main>` for the children: every child request appends `?user='+$user` so
   the app can tell tabs apart (see `{{top/ns}}.{{main/ns}}.web.core/wrap-logged-user`).
   A top-level signal rather than `$home.user` because Datastar RC8 drops the
   closing paren of `@post('…'+$home.user)` when it rewrites a dotted signal path."
  (:require
   [clojure.string :as str]
   [taia.ds.datastar.interface :as ds]
   [{{top/ns}}.{{main/ns}}.web.components.home.home-controller :as home-controller]
   [{{top/ns}}.{{main/ns}}.web.inspect :as inspect]))

(def url
  "The page's own component route: a GET here is the page's INIT, so the
   examples need no route of their own. `?example=<name>` picks the example."
  "/ds/nds/home.home/home/init")

(defn page-url [name] (str url "?example=" name))

(def ^:private css
  "body{font-family:system-ui,sans-serif;margin:0;background:#f6f7f9;color:#1d2330}
   header{background:#1d2330;color:#fff;padding:.75rem 1.25rem}header a{color:#fff;text-decoration:none;font-weight:600}
   main{max-width:720px;margin:1.5rem auto;padding:0 1rem}
   .card{background:#fff;border-radius:12px;padding:1.25rem 1.5rem;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:1rem}
   .muted{color:#6b7280;font-size:.9rem}.big{font-size:2.5rem;font-weight:700;margin:.5rem 0}
   .row{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center}
   button{border:0;border-radius:8px;padding:.5rem 1rem;background:#2563eb;color:#fff;font-size:1rem;cursor:pointer}
   button.secondary{background:#e5e7eb;color:#1d2330}button:disabled{opacity:.5}
   pre{background:#f3f4f6;padding:.75rem;border-radius:8px;overflow:auto;font-size:.85rem}
   details.inspect{margin-top:1rem;border-top:1px dashed #d1d5db;padding-top:.5rem}details.inspect summary{cursor:pointer;font-size:.8rem}details.inspect pre{margin:.5rem 0 0;font-size:.8rem}
   .index li{margin:.75rem 0}.index a{font-weight:600}
   footer{max-width:720px;margin:2rem auto;padding:0 1rem}")

(defn- init-url
  "The child's INIT: /ds/nds/… answers once; /ds/nds-connect/… opens the
   connection a connected component's server events (`send-event!`) need."
  [{:keys [component cid connected?]}]
  (str (if connected? "/ds/nds-connect/" "/ds/nds/") component "/" cid "/init"))

(defn- mount
  "The element the child component is mounted in: a wrapper whose `data-init`
   POSTs the child's INIT, around the `#cid` element the child's render
   replaces. A connected child's wrapper also re-opens the connection when the
   reconnect watchdog (sse-reconnect.js) fires `ds-reconnect` on it. The
   marker sits on the wrapper because the render morphs `#cid` and drops
   every attribute it does not render itself."
  [{:keys [cid connected?] :as example}]
  (let [init (str "@post('" (init-url example) "?user='+$user)")]
    [:div (cond-> {:id (str cid "-mount") "data-init" init}
            connected? (assoc "data-on:ds-reconnect" init))
     [:div {:id cid} [:p.muted "loading…"]]]))

(defn- index []
  [[:h1 "{{main}}"]
   [:p.muted "Built on taia.ds. Each page mounts one component; their source lives under "
    [:code "bases/web/src/{{top/file}}/{{main/file}}/web/components/"] "."]
   (into [:ol.index]
         (for [{:keys [example title summary]} home-controller/examples]
           [:li [:a {:href (page-url example)} title] [:br] [:span.muted summary]]))])

(defn- example-page [{:keys [title summary component doc] :as example} user]
  [[:h1 title]
   [:p.muted summary]
   (mount example)
   [:p.muted "Component " [:code component] " · signed in as " [:code user]
    (when doc [:span " · " [:a {:href (str "https://github.com/DanBunea/taia.ds/blob/main/docs/examples/" doc)} "read the walkthrough"]])]])

(defn- not-found [name]
  [[:h1 "No such page"]
   [:p.muted (str "'" name "' is not one of: " (str/join ", " (map :example home-controller/examples)))]])

(defn home
  "Full-page hiccup: loads datastar.js (served from the taia.ds jar's resources
   via `wrap-resource \"datastar\"`) and the reconnect watchdog. The `<body>` is
   the component's root element."
  [{:keys [component-id state memory selected] :as ctx}]
  (let [title (case state :index "Home" :example (:title selected) "Not found")]
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title (str title " · {{main}}")]
      [:style css]
      [:script {:type "module" :src "/js/datastar.js"}]
      [:script {:src "/js/sse-reconnect.js" :defer true}]]
     [:body {:id component-id :data-signals (ds/ctx->data-signals ctx)}
      [:header [:a {:href url} "{{main}}"]]
      (into [:main {:data-signals (str "{user:'" (:user memory) "'}")}]
            (case state
              :index     (index)
              :example   (example-page selected (:user memory))
              :not-found (not-found (:example memory))))
      [:footer (inspect/state-pre ctx "home-inspect")]]]))
