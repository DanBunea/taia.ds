(ns taia.ds.examples.components.weather.weather-api
  "Current weather for a city from Open-Meteo (free, no key): geocode the
   name, then read the current conditions. Pure JDK HTTP client + charred.

   Throws `ex-info` with a user-facing `:message` on an unknown place or a
   failing service — the invoke's `:on-error` path shows it.

   With TAIA_EXAMPLES_FAKE_WEATHER set (the e2e runner does) the lookup is
   answered from a small table after a short delay, so the suite is
   deterministic and the loading state is observable."
  (:require
   [charred.api :as charred]
   [clojure.string :as str])
  (:import
   [java.net URI URLEncoder]
   [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
   [java.time Duration]))

(def ^:private conditions
  "WMO weather interpretation codes, abbreviated."
  {0 "Clear sky" 1 "Mainly clear" 2 "Partly cloudy" 3 "Overcast"
   45 "Fog" 48 "Rime fog"
   51 "Light drizzle" 53 "Drizzle" 55 "Dense drizzle"
   61 "Light rain" 63 "Rain" 65 "Heavy rain"
   71 "Light snow" 73 "Snow" 75 "Heavy snow" 77 "Snow grains"
   80 "Showers" 81 "Showers" 82 "Violent showers"
   95 "Thunderstorm" 96 "Thunderstorm with hail" 99 "Thunderstorm with hail"})

(defn describe-code [code]
  (get conditions code (str "Weather code " code)))

(def ^:private client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) .build)))

(defn- refuse [message]
  (throw (ex-info message {:message message})))

(defn- get-json [url]
  (let [request  (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 8))
                     (.header "Accept" "application/json")
                     .GET .build)
        response (try (.send @client request (HttpResponse$BodyHandlers/ofString))
                      (catch Exception e
                        (refuse (str "Could not reach the weather service (" (.getSimpleName (class e)) ")."))))]
    (when-not (= 200 (.statusCode response))
      (refuse (str "The weather service answered " (.statusCode response) ".")))
    (charred/read-json (.body response) :key-fn keyword)))

(defn- geocode [city]
  (let [{:keys [results]} (get-json (str "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                                         (URLEncoder/encode (str city) "UTF-8")))]
    (or (first results)
        (refuse (str "No place called '" city "' was found.")))))

(defn- current [{:keys [latitude longitude]}]
  (:current (get-json (str "https://api.open-meteo.com/v1/forecast?current=temperature_2m,wind_speed_10m,weather_code"
                           "&latitude=" latitude "&longitude=" longitude))))

(def ^:private fake-table
  {"barcelona" {:place "Barcelona, Spain" :temperature 19.1 :wind 2.9 :code 3}
   "oslo"      {:place "Oslo, Norway" :temperature -1.4 :wind 11.0 :code 71}
   "tokyo"     {:place "Tokyo, Japan" :temperature 14.6 :wind 7.2 :code 1}
   "lima"      {:place "Lima, Peru" :temperature 21.0 :wind 13.5 :code 2}})

(defn fake?
  "True when TAIA_EXAMPLES_FAKE_WEATHER is set (e2e runs)."
  []
  (some? (System/getenv "TAIA_EXAMPLES_FAKE_WEATHER")))

(defn- fake-weather [city]
  (Thread/sleep 700)
  (if-let [w (get fake-table (str/lower-case (str/trim (str city))))]
    (assoc w :condition (describe-code (:code w)) :observed-at "now (fake data)")
    (refuse (str "No place called '" city "' was found."))))

(defn fetch-current-weather
  "{:place :temperature :wind :condition :observed-at} for `city`, or throws
   ex-info {:message …}."
  [city]
  (when (str/blank? (str city)) (refuse "Type a city first."))
  (if (fake?)
    (fake-weather city)
    (let [{:keys [name country] :as place} (geocode city)
          {:keys [temperature_2m wind_speed_10m weather_code time]} (current place)]
      {:place       (str name (when country (str ", " country)))
       :temperature temperature_2m
       :wind        wind_speed_10m
       :condition   (describe-code weather_code)
       :observed-at time})))
