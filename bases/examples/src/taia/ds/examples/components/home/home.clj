(ns taia.ds.examples.components.home.home
  "The examples home page as a Datastar component: `<body>` is the component's root
   (id `home`, the page's signals on it), and each example page mounts ONE
   child component — a `div` whose `data-init` fires the child's INIT, either a
   one-shot `@post` to /ds/nds/… or, for connected components, an `@get` to
   /ds/nds-connect/… that keeps the SSE stream open.

   Two signal stores on the page: the page's own (`$home.*`, on `<body>`,
   what this component round-trips) and a page-level `$user`, seeded on
   `<main>` for the children: every child request appends `?user='+$user` so
   the examples app can tell tabs apart (see `taia.ds.examples.core/wrap-logged-user`)
   while broadcast fragments stay user-neutral. A top-level signal rather than
   `$home.user` because Datastar RC8 drops the closing paren of
   `@post('…'+$home.user)` when it rewrites a dotted signal path."
  (:require
   [clojure.string :as str]
   [taia.ds.datastar.interface :as ds]
   [taia.ds.examples.components.home.home-controller :as home-controller]
   [taia.ds.examples.inspect :as inspect]))

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
   input[type=text],input[type=email]{border:1px solid #d1d5db;border-radius:8px;padding:.5rem .75rem;font-size:1rem;width:100%;box-sizing:border-box}
   label{display:block;margin:.75rem 0 .25rem;font-weight:600}.error{color:#b91c1c;font-size:.9rem}.ok{color:#15803d}
   .steps{display:flex;gap:.5rem;margin-bottom:1rem}.steps span{padding:.25rem .75rem;border-radius:999px;background:#e5e7eb}.steps span.active{background:#2563eb;color:#fff}
   ul.todos{list-style:none;padding:0;margin:0}ul.todos li{display:flex;gap:.5rem;align-items:center;padding:.5rem 0;border-bottom:1px solid #eee}
   ul.todos li.completed .text{text-decoration:line-through;color:#9ca3af}ul.todos li .text{flex:1;cursor:pointer}
   .filters a{margin-right:.5rem;cursor:pointer;color:#2563eb}.filters a.selected{font-weight:700;text-decoration:underline}
   pre{background:#f3f4f6;padding:.75rem;border-radius:8px;overflow:auto;font-size:.85rem}
   details.inspect{margin-top:1rem;border-top:1px dashed #d1d5db;padding-top:.5rem}details.inspect summary{cursor:pointer;font-size:.8rem}details.inspect pre{margin:.5rem 0 0;font-size:.8rem}
   .index li{margin:.75rem 0}.index a{font-weight:600}
   footer{max-width:720px;margin:2rem auto;padding:0 1rem}")

(defn- init-url [{:keys [component cid connected?]}]
  (str (if connected? "/ds/nds-connect/" "/ds/nds/") component "/" cid "/init"))

(defn- mount
  "The element the example's component is mounted in."
  [{:keys [cid connected?] :as example}]
  [:div {:id cid
         "data-init" (str (if connected? "@get('" "@post('") (init-url example) "?user='+$user)")}
   [:p.muted "loading…"]])

(defn- index []
  [[:h1 "Examples"]
   [:p.muted "A progression through the framework. Each page mounts one component; the source of every example lives under "
    [:code "bases/examples/src/taia/ds/examples/components/"] "."]
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
  [[:h1 "No such example"]
   [:p.muted (str "'" name "' is not one of: " (str/join ", " (map :example home-controller/examples)))]])

(defn home
  "Full-page hiccup: loads datastar.js (served from the library's resources via
   `wrap-resource \"datastar\"`) and the reconnect watchdog. The `<body>` is the
   component's root element."
  [{:keys [component-id state memory selected] :as ctx}]
  (let [title (case state :index "Examples" :example (:title selected) "Not found")]
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title (str title " · taia.ds examples")]
      [:style css]
      [:script {:type "module" :src "/js/datastar.js"}]
      [:script {:src "/js/sse-reconnect.js" :defer true}]]
     [:body {:id component-id :data-signals (ds/ctx->data-signals ctx)}
      [:header [:a {:href url} "taia.ds examples"]]
      (into [:main {:data-signals (str "{user:'" (:user memory) "'}")}]
            (case state
                      :index     (index)
                      :example   (example-page selected (:user memory))
                      :not-found (not-found (:example memory))))
      [:footer (inspect/state-pre ctx "home-inspect")]]]))
