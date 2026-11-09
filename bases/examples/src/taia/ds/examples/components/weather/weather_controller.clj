(ns taia.ds.examples.components.weather.weather-controller
  "State machine for example 6.

   INIT → :loading   renders the skeleton, which posts LOAD on its own
   LOAD → :fetching  the invoke runs here, synchronously, in the LOAD request
          :on-done  → :loaded with the weather in top-level ctx
          :on-error → :failed with the message in top-level ctx
   SEARCH            captures ?city= into :memory FIRST (an invoke never sees
                     event params), then re-enters :fetching.

   INIT is machine-wide so a reconnect or a second init re-fetches from any
   state instead of being dropped."
  (:require
   [taia.ds.examples.components.weather.weather-api :as api]))

(def default-city "Barcelona")

(defn- seed [ctx _]
  (update-in ctx [:memory :city] #(if (seq %) % default-city)))

(defn- capture-city
  "Query-string `city` (the quick picks) wins over the bound input; without
   it the input's value in :memory is used as-is."
  [ctx {:keys [city]}]
  (if (seq city) (assoc-in ctx [:memory :city] city) ctx))

(defn fetch-weather!
  "The invoke. Gets the ctx only — the city has to be in :memory by now."
  [ctx]
  {:weather (api/fetch-current-weather (get-in ctx [:memory :city]))})

(def fetching
  {:invoke {:id       :fetch-weather
            :src      fetch-weather!
            :on-done  {:target :loaded :actions (fn [ctx result] (assoc ctx :weather (:weather result)))}
            :on-error {:target :failed :actions (fn [ctx err] (assoc ctx :error (:message err)))}}})

(def machine
  {:id      :weather
   :initial :loading
   :on      {:INIT {:target :loading :actions seed}}
   :states  {:loading  {:on {:LOAD :fetching}}
             :fetching fetching
             :loaded   {:on {:SEARCH {:target :fetching :actions capture-city}}}
             :failed   {:on {:LOAD   :fetching
                             :SEARCH {:target :fetching :actions capture-city}}}}})
