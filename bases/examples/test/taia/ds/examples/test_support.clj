(ns taia.ds.examples.test-support
  "Helpers for testing components WITHOUT a server: drive the pipeline in
   process and inspect the resulting `:memory`, `:state` and rendered HTML.
   Copy this file into your application's test dir; it is deliberately
   small. Hickory parses the rendered HTML so assertions target
   `data-testid`s instead of string matching."
  (:require
   [dev.onionpancakes.chassis.compiler :as hc]
   [dev.onionpancakes.chassis.core :as h]
   [hickory.core :as hickory]
   [hickory.select :as s]
   [taia.ds.datastar.interface :as ds]))

(defn request
  "A request shaped like the one the handlers see: the signal store under the
   component-id (with the FSM state), event params as query params, a logged user."
  [cid {:keys [memory state params user]}]
  {:params      (merge {:logged-user {:user/id (or user "tester")}} params)
   :body-params {(keyword cid) (cond-> (or memory {}) state (assoc :state state))}})

(defn run
  "Runs `event` (lowercase, as in the URL) for `component` with the given
   `:memory`/`:state`/`:params`. Returns {:memory :state :html :ctx} — `:html` is
   hiccup, or an effects list when the render fn returned one; `:ctx` is the
   whole post-transition context (top-level keys an invoke added live there)."
  [component cid opts event]
  (let [{:keys [ctx html]} (ds/process-component-event (request cid opts) component cid event nil)]
    {:memory (:memory ctx) :state (:state ctx) :html html :ctx ctx}))

(defn step
  "Feeds the previous `run` result back in as the next request's store — what
   the browser does between two events. `params` are the event's query params."
  ([component cid prev event] (step component cid prev event nil))
  ([component cid {:keys [memory state]} event params]
   (run component cid {:memory memory :state state :params params} event)))

(defn render-to-hickory
  "Renders hiccup (or a seq of hiccup) to a parsed hickory tree."
  [hiccup]
  (-> (h/html (hc/compile (into [:div#test-root] (if (and (vector? hiccup) (keyword? (first hiccup))) [hiccup] hiccup))))
      hickory/parse
      hickory/as-hickory))

(defn select-by-testid [parsed tid]
  (first (s/select (s/attr :data-testid #(= % tid)) parsed)))

(defn text-of
  "Concatenated text content of a hickory node."
  [node]
  (letfn [(collect [x] (cond (string? x) [x]
                             (sequential? x) (mapcat collect x)
                             (map? x) (collect (:content x))
                             :else []))]
    (apply str (collect (:content node)))))

(defn testid-text
  "Text of the element with `tid` in rendered `hiccup`, or nil."
  [hiccup tid]
  (some-> (render-to-hickory hiccup) (select-by-testid tid) text-of))

(defn extract-post-urls
  "Every @post('…') URL in the rendered markup."
  [hiccup]
  (->> (tree-seq coll? seq (render-to-hickory hiccup))
       (filter map?)
       (mapcat #(vals (:attrs %)))
       (filter string?)
       (mapcat #(re-seq #"@post\('([^']+)'" %))
       (mapv second)))
