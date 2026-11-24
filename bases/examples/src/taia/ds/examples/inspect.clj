(ns taia.ds.examples.inspect
  "The little state viewer under every example: what the component keeps
   between requests, exactly as the pipeline sees it after the transition.
   It sits INSIDE the component's root element so each morph refreshes it."
  (:require
   [clojure.pprint :as pprint]
   [hyperfiddle.rcf :refer [tests]]))

(defn state-pre
  "`[:pre …]` with the pretty-printed `:state` and `:memory` of `ctx`. The
   `testid` defaults to \"inspect\"; the home page passes its own so a page
   never carries two elements with the same test id."
  ([ctx] (state-pre ctx "inspect"))
  ([ctx testid]
   [:details.inspect {:open true :data-testid testid}
    [:summary.muted "state + memory (what round-trips through the signal store)"]
    [:pre (with-out-str (pprint/pprint (select-keys ctx [:state :memory])))]]))

(tests
 "the state and memory of the ctx, pretty-printed; nothing else from the ctx"
 (state-pre {:component-id "counter1" :state :displayed :memory {:count 2} :logged-user {:user/id "u"}})
 := [:details.inspect {:open true :data-testid "inspect"}
     [:summary.muted "state + memory (what round-trips through the signal store)"]
     [:pre "{:state :displayed, :memory {:count 2}}\n"]]

 "a page passes its own test id, so it never carries two elements with the same one"
 (state-pre {:state :index :memory {}} "home-inspect")
 := [:details.inspect {:open true :data-testid "home-inspect"} _ _])
