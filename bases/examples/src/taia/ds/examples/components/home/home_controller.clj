(ns taia.ds.examples.components.home.home-controller
  "The page itself is a component. Its only event is INIT, fired in-process by
   a GET on its own route with `?example=<name>` as the event param; three
   guarded options land it in :index, :example or :not-found. What the page
   keeps in `:memory` is what a page would keep in signals: the user and the
   example's name. The resolved registry entry is derived data, so it lives in
   top-level ctx (`:selected`), not `:memory`. The registry lives here too: it
   is what INIT resolves the name against.")

(def examples
  "Ordered progression. `:component` is `<group>.<name>` under the search path
   `taia.ds.examples.components`; `:cid` is the component-id (also the DOM id
   and the signal namespace), kept free of dashes so signal paths stay simple."
  [{:example "counter" :title "1 · Counter" :component "basics.counter" :cid "counter1"
    :summary "One-shot events, `:memory` round-trip through the signal store, the render fn as the single source of markup."
    :doc "01-counter.md"}
   {:example "live-counter" :title "2 · Live counter" :component "live.live-counter" :cid "live1" :connected? true
    :summary "A connected component: an SSE stream per tab, server-owned state, `send-ui!` fan-out to everyone. Open it twice with `?user=alice` and `?user=bob`."
    :doc "02-live-counter.md"}
   {:example "signup" :title "3 · Signup form" :component "signup.signup" :cid "signup1"
    :summary "Client-owned form signals with `data-bind`, guarded transitions (`:cond`), an effects list with `:execute-script`."
    :doc "03-signup-form.md"}
   {:example "wizard" :title "4 · Wizard" :component "wizard.wizard" :cid "wizard1"
    :summary "Hierarchical states, a machine-wide RESET, an invoke with `:on-done` / `:on-error`."
    :doc "04-wizard.md"}
   {:example "todomvc" :title "5 · TodoMVC" :component "todomvc.todomvc" :cid "todos1"
    :summary "The classic, entirely in `:memory`: query-string event params, filters, inline editing, keyboard events."
    :doc "05-todomvc.md"}
   {:example "weather" :title "6 · Weather" :component "weather.weather" :cid "weather1"
    :summary "An invoke calling a public API (Open-Meteo), a lazy-loading skeleton, `data-indicator` while a request is in flight, the error path."
    :doc "06-weather.md"}])

(defn find-example [name]
  (first (filter #(= name (:example %)) examples)))

(defn- remember [ctx {:keys [example]}]
  (-> ctx
      (assoc-in [:memory :user] (get-in ctx [:logged-user :user/id]))
      (cond-> example (assoc-in [:memory :example] example))))

(defn- lookup [ctx {:keys [example] :as params}]
  (assoc (remember ctx params) :selected (find-example example)))

(defn- index? [_ {:keys [example]}] (nil? example))
(defn- known? [_ {:keys [example]}] (some? (find-example example)))

(def machine
  {:id      :home
   :initial :index
   :on      {:INIT [{:target :index     :cond index? :actions remember}
                    {:target :example   :cond known? :actions lookup}
                    {:target :not-found :actions remember}]}
   :states  {:index {} :example {} :not-found {}}})
