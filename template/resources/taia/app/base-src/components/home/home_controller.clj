(ns {{top/ns}}.{{main/ns}}.web.components.home.home-controller
  "The page itself is a component. Its only event is INIT, fired in-process by
   a GET on its own route with `?example=<name>` as the event param; three
   guarded options land it in :index, :example or :not-found. What the page
   keeps in `:memory` is what a page would keep in signals: the user and the
   example's name. The resolved registry entry is derived data, so it lives in
   top-level ctx (`:selected`), not `:memory`.

   A new component = a `<group>/<name>.clj` render fn + a
   `<group>/<name>_controller.clj` machine under `components/`, and an entry
   in `examples` below.")

(def examples
  "The registry. `:component` is `<group>.<name>` under the search path
   `{{top/ns}}.{{main/ns}}.web.components`; `:cid` is the component-id (also the
   DOM id and the signal namespace), kept free of dashes so signal paths stay
   simple. `:connected? true` mounts it over an SSE stream that stays open."
  [{:example "counter" :title "Counter" :component "basics.counter" :cid "counter1"
    :summary "One-shot events, `:memory` round-trip through the signal store, the render fn as the single source of markup."
    :doc "01-counter.md"}
   {:example "live-counter" :title "Live counter" :component "live.live-counter" :cid "live1" :connected? true
    :summary "A connected component with two ways in: a click and a server event (`send-event!`) go through the same in-chan."
    :doc "02-live-counter.md"}])

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
