(ns taia.ds.examples.components.weather.weather
  "Example 6 — Weather: an invoke that calls a public API, and two kinds of
   loading state.

   1. The LAZY LOAD. INIT does not fetch. It renders a skeleton whose
      `data-init` immediately posts LOAD; LOAD enters `:fetching`, whose
      `:invoke` calls Open-Meteo and lands in `:loaded` or `:failed`. The
      page therefore paints at once and the slow part happens on a second
      request — the pattern for anything that must not block first paint.
   2. The IN-FLIGHT INDICATOR. While a search request is pending, Datastar
      sets the `$_weatherBusy` signal (`data-indicator`) so the spinner shows
      and the buttons disable, with no server involvement. The underscore
      keeps the signal local: it never travels to the server.

   The fetched weather lives in TOP-LEVEL ctx, not `:memory`: it is derived
   data, re-fetched on demand, and must not be echoed back from the client.
   Only the city (the user's choice) is in the store."
  (:require
   [taia.ds.examples.inspect :as inspect]
   [taia.ds.datastar.interface :as ds]))

(def quick-picks ["Barcelona" "Oslo" "Tokyo" "Lima"])

(defn- post
  ([cid event] (post cid event nil))
  ([cid event query] (str "@post('/ds/nds/weather.weather/" cid "/" event (when query (str "?" query)) "')")))

(def ^:private busy "_weatherBusy")

(defn- search-bar [cid]
  (list
   [:div.row {:style {:margin-top "1rem"}}
    [:input {:type "text" :placeholder "City" :data-testid "city" :style {:flex 1}
             "data-bind" (str cid ".city")
             "data-on:keydown" (str "evt.key==='Enter' && " (post cid "search"))}]
    [:button {:type "button" :data-testid "search" :data-indicator busy
              "data-attr:disabled" (str "$" busy)
              "data-on:click" (post cid "search")} "Look up"]
    [:span.muted {:data-testid "busy" "data-show" (str "$" busy)} "fetching…"]]
   (into [:div.row {:style {:margin-top ".5rem"}}]
         (for [city quick-picks]
           [:button.secondary {:type "button" :data-testid (str "pick-" city) :data-indicator busy
                               "data-attr:disabled" (str "$" busy)
                               "data-on:click" (post cid "search" (str "city=" city))} city]))))

(defn- loading [cid memory]
  [:div {:data-testid "loading"
         ;; fires once, as soon as this skeleton is in the DOM
         "data-init" (post cid "load")}
   [:p.big "…"]
   [:p.muted (str "Loading the weather in " (:city memory) "…")]])

(defn- loaded [{:keys [place temperature wind condition observed-at]}]
  [:div {:data-testid "weather"}
   [:p.muted {:data-testid "place"} place]
   [:p.big {:data-testid "temperature"} (str temperature " °C")]
   [:p (str condition " · wind " wind " km/h")]
   [:p.muted (str "Observed " observed-at " · data by open-meteo.com")]])

(defn- failed [error]
  [:div
   [:p.error {:data-testid "error"} error]])

(defn weather [{:keys [component-id state memory] :as ctx}]
  [:div.card {:id component-id :data-signals (ds/ctx->data-signals ctx)}
   [:h2 "Weather"]
   (case state
     :loading (loading component-id memory)
     :loaded  (loaded (:weather ctx))
     :failed  (failed (:error ctx))
     [:p.muted (str "state: " (pr-str state))])
   (when (#{:loaded :failed} state)
     (search-bar component-id))
   [:p.muted "State: " [:code {:data-testid "state"} (pr-str state)]]
   (inspect/state-pre ctx)])
