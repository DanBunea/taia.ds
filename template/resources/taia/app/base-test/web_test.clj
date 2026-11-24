(ns {{top/ns}}.{{main/ns}}.web.web-test
  "The components, driven through the real pipeline (discovery → FSM → render)
   with no HTTP — see `test-support`.

   `run`/`step` return {:memory :state :html :ctx}. The assertions compare that
   whole map with RCF's `_` wildcard on the parts a case is not about — so an
   assertion reads as \"after this event the signal store is exactly this\",
   and a stray key fails it.

   Every case writes where it starts from literally (a `{:memory :state}`
   store, as the browser would send it) instead of chaining on the previous
   case's result. Each block has a small helper that runs one event of its
   component from such a store."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.datastar.interface :as ds]
   [{{top/ns}}.{{main/ns}}.web.test-support :as ts :refer [run step testid-text]]))

(tests
 (defn- home-init [params]
   (run "home.home" "home" {:params params} "init"))

 "0 home: the page is a component — INIT with no example is the index"
 (home-init nil) := {:memory {:user "tester"} :state :index :html _ :ctx _}
 (-> (home-init nil)
     :html
     ts/extract-post-urls)
 := []

 "0 home: a known example lands in :example; the resolved entry is derived (ctx :selected), the name is remembered (memory)"
 (home-init {:example "counter"}) := {:memory {:user "tester" :example "counter"} :state :example :html _ :ctx _}
 (-> (home-init {:example "counter"})
     :ctx
     :selected
     (select-keys [:cid :component]))
 := {:cid "counter1" :component "basics.counter"}

 "0 home: the mount fires the child's INIT with the page-level user signal"
 (-> (home-init {:example "counter"})
     :html
     ts/extract-post-urls)
 := ["/ds/nds/basics.counter/counter1/init?user="]

 "0 home: the page's own state viewer shows where the page is"
 (-> (home-init {:example "counter"})
     :html
     (testid-text "home-inspect")
     (str/includes? ":state :example"))
 := true

 "0 home: an unknown example lands in :not-found"
 (home-init {:example "nope"})
 := {:memory {:user "tester" :example "nope"} :state :not-found :html _ :ctx _})

(tests
 (defn- counter-event [from event]
   (step "basics.counter" "counter1" from event))

 "1 counter: INIT seeds 0"
 (counter-event {} "init") := {:memory {:count 0} :state :displayed :html _ :ctx _}
 (-> (counter-event {} "init")
     :html
     (testid-text "count"))
 := "0"

 "1 counter: a count already in the store survives INIT (reconnect / second init)"
 (counter-event {:memory {:count 7}} "init")
 := {:memory {:count 7} :state :displayed :html _ :ctx _}

 "1 counter: INCREMENT, DECREMENT and RESET change the count in the store"
 (counter-event {:memory {:count 2} :state :displayed} "increment") := {:memory {:count 3} :state :displayed :html _ :ctx _}
 (counter-event {:memory {:count 2} :state :displayed} "decrement") := {:memory {:count 1} :state :displayed :html _ :ctx _}
 (counter-event {:memory {:count 2} :state :displayed} "reset") := {:memory {:count 0} :state :displayed :html _ :ctx _}

 "1 counter: increments accumulate through the round-tripped store"
 (-> {:memory {:count 0} :state :displayed}
     (counter-event "increment")
     (counter-event "increment"))
 := {:memory {:count 2} :state :displayed :html _ :ctx _}

 "1 counter: the render shows the count, and the state viewer under every
  example prints exactly what round-trips"
 (-> (counter-event {:memory {:count 1} :state :displayed} "increment")
     :html
     (testid-text "count"))
 := "2"
 (-> (counter-event {:memory {:count 1} :state :displayed} "increment")
     :html
     (testid-text "inspect")
     (str/includes? "{:state :displayed, :memory {:count 2}}"))
 := true

 "1 counter: every button posts to this component's own route"
 (-> (counter-event {} "init")
     :html
     ts/extract-post-urls
     set)
 := #{"/ds/nds/basics.counter/counter1/decrement"
      "/ds/nds/basics.counter/counter1/increment"
      "/ds/nds/basics.counter/counter1/reset"})

(tests
 (defn- server-event
   "A server event (what `send-event!` sends) through a
    fresh live-counter flow: no request, no signals — only `params`."
   [event params]
   (let [[cf _] (ts/connect "live.live-counter" "live1" {})]
     (try
       (ts/server-event cf event params)
       (finally
         (ds/stop-component-flow! cf)))))

 "2 live counter: INIT seeds the count at 0, in :memory"
 (run "live.live-counter" "live1" {} "init") := {:memory {:count 0} :state :displayed :html _ :ctx _}

 "2 live counter: a click starts from the count in the tab's signals"
 (run "live.live-counter" "live1" {:memory {:count 1} :state :displayed} "increment")
 := {:memory {:count 2} :state :displayed :html _ :ctx _}
 (-> (run "live.live-counter" "live1" {:memory {:count 1} :state :displayed} "increment")
     :html
     (testid-text "count"))
 := "2"

 "2 live counter: a server event has no signals, so it brings the count as
  its params: (ds/send-event! \"live1\" user \"increment\" {:count 2})"
 (server-event "increment" {:count 2}) := {:memory {:count 3} :state :displayed :html _ :ctx _}
 (-> (server-event "increment" {:count 2})
     :html
     (testid-text "count"))
 := "3"

 "2 live counter: the render carries the count in data-signals, so a render
  pushed down the stream updates the tab's store for its next click"
 (-> (server-event "increment" {:count 2})
     :html
     second
     :data-signals
     (str/includes? "\"count\":3"))
 := true

 "2 live counter: RESET puts the count back to 0, from a click or a server event"
 (run "live.live-counter" "live1" {:memory {:count 3} :state :displayed} "reset")
 := {:memory {:count 0} :state :displayed :html _ :ctx _}
 (server-event "reset" {}) := {:memory {:count 0} :state :displayed :html _ :ctx _})

