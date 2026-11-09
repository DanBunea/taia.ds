(ns taia.ds.examples.inspect
  "The little state viewer under every example: what the component keeps
   between requests, exactly as the pipeline sees it after the transition.
   It sits INSIDE the component's root element so each morph refreshes it."
  (:require
   [clojure.pprint :as pprint]))

(defn state-pre
  "`[:pre …]` with the pretty-printed `:state` and `:memory` of `ctx`."
  [ctx]
  [:details.inspect {:open true :data-testid "inspect"}
   [:summary.muted "state + memory (what round-trips through the signal store)"]
   [:pre (with-out-str (pprint/pprint (select-keys ctx [:state :memory])))]])
