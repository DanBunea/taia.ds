# Testing components

Most of a component is testable without HTTP, a browser or Datastar: drive the pipeline in process
and look at `:memory`, `:state` and the rendered hiccup.

## Driving the pipeline

```clojure
(require '[taia.ds.datastar.interface :as ds])

(ds/set-component-search-paths! ["my.app.components"])

(def req {:params      {:logged-user {:user/id "tester"} :id "3"}       ; event params = query params
          :body-params {:todos1 {:todos [...] :state "displayed"}}})    ; the signal store

(let [{:keys [ctx html]} (ds/process-component-event req "todomvc.todomvc" "todos1" "toggle" nil)]
  (:memory ctx)   ; after the transition
  (:state ctx)
  html)           ; hiccup, or an effects list
```

The examples base wraps exactly this in
[`test_support.clj`](../bases/examples/test/taia/ds/examples/test_support.clj): `run` builds the
request from `{:memory :state :params :user}`, `step` feeds one result into the next request the
way the browser does, and `testid-text` / `extract-post-urls` parse the rendered HTML with
[hickory](https://github.com/clj-commons/hickory) so assertions target `data-testid` attributes, not
strings. Copy the file; it is forty lines.

```clojure
(def c0 (run "basics.counter" "counter1" {} "init"))
(def c1 (step "basics.counter" "counter1" c0 "increment"))
(:count (:memory c1)) := 1
(testid-text (:html c1) "count") := "1"
```

[`examples_test.clj`](../bases/examples/test/taia/ds/examples/examples_test.clj) is the complete
executable spec of the five examples and the best place to see the style.

## Testing the machine alone

`taia.ds.state-machines.interface/transition` is pure: `(transition machine {:state :a :memory {}} [:GO {}] {:state-key :state})`.
For machines whose invokes hit the world, `make-machine-testable` swaps named functions for their
vars so `with-redefs` can intercept them, and `taia.ds.state-machines.test-helpers/test-machine`
asserts targets and action traces declaratively without running invokes.

## Testing the render fn alone

Call it with a hand-made ctx and parse the result with hickory. Two traps:

- The render fn's top-level ctx keys are only real if the invoke or action before **that** event
  stamps them. A render test that passes the key by hand passes while production renders nil.
  Test the transition → render path together for anything beyond `:memory`.
- Never gate a render on `(:event ctx)`; the event is not in ctx.

## What needs a browser

Datastar itself: `data-bind` writing into the store, morph behaviour, `data-init` firing, the SSE
stream and the reconnect watchdog. Cover those with an end-to-end suite against a real page
(Playwright or etaoin) and **watch the browser console**: a thrown Datastar expression aborts the
attribute pass for the whole patched subtree without any server-side symptom.

## Conventions used in this repository

Tests are inline RCF `(tests …)` blocks (`hyperfiddle.rcf`), run by Kaocha with
`-Dhyperfiddle.rcf.generate-tests=true` (`bb test`). Values are bound with `def` so they can be
inspected in a REPL; assertions are top-level `:=`. Because the dev server enables RCF, a
lazily-loaded component namespace runs its tests on first request — keep test blocks free of
process-global side effects, or restore what they change.
