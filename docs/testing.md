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

Start each case from the store it is about, rather than chaining one case's result into the
next; keep `step` for the cases where the round trip itself is the point:

```clojure
(def ^:private at-one {:memory {:count 1} :state :displayed})

"an increment from 1"
(-> (run "basics.counter" "counter1" at-one "increment")
    (select-keys [:memory :state]))
:= {:memory {:count 2} :state :displayed}

"the render shows the new count"
(-> (run "basics.counter" "counter1" at-one "increment")
    :html
    (testid-text "count"))
:= "2"
```

[`examples_test.clj`](../bases/examples/test/taia/ds/examples/examples_test.clj) is the complete
executable spec of the examples and the best place to see the style.

## How the tests are written

The same rules hold for the library's own tests (inline RCF `(tests …)` blocks):

- **One function per test.** A function that hands work to another one is tested with that
  collaborator mocked by `spy.core`, asserting both its return value and
  `(spy/called-once-with? collaborator args…) := true`; the collaborator has tests of its own.
  The flow procs are the reference: `process-event.controller` tests `handle-event` directly, and
  `step` with `handle-event` spied.
- **Standalone cases.** Each case starts from a named value of its own (`at-one`,
  `holding-running`), never from the result of the case before it.
- **Threads one step per line, from the input:**
  `(-> holding-running` / `(handle-event {:event ["increment" {}]})` / `sent` / `:ctx)`.
- **Whole values.** Compare the full expected map or vector with `:=`; narrow with `select-keys`
  or a named helper only when the case is about one part.

## Driving a connected component's flow

A connected component keeps its ctx in its flow between events, so `run`/`step` (which rebuild the
ctx from the request every time) cannot follow it. Start the flow headless instead:

```clojure
(def cf (ds/start-component-flow! {:component-name "live.countdown" :component-id "countdown1"
                                   :keep-ctx? true}))
(ds/run-flow-event! cf req "init")     ; a browser event: req carries signals and params
(ds/run-flow-event! cf req "start")
(ds/run-flow-event! cf nil "tick")     ; a server event, like send-event!: no request at all
(ds/stop-component-flow! cf)
```

`test_support.clj` wraps these as `connect`, `flow-event` and `server-event`; block 7 of
`examples_test.clj` drives the countdown with them.

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
stream and the reconnect watchdog. Cover those with an end-to-end suite against a real page and
**watch the browser console**: a thrown Datastar expression aborts the attribute pass for the whole
patched subtree without any server-side symptom.

### End-to-end (Playwright)

```shell
bb e2e-test                 # all specs; needs Node >= 18
bb e2e-test counter         # one spec file
bb e2e-test --headed
```

`scripts/e2e.clj` installs `e2e/` deps, starts the examples app on port 7799 in its own JVM, runs
`npx playwright test` with `E2E_BASE_URL` set, and stops the JVM (log in
`e2e/test-results/server.log`). Against an already running `bb dev`:
`E2E_BASE_URL=http://localhost:7788 npx playwright test` from `e2e/`.

One spec per example in [`e2e/tests/`](../e2e/tests/). Every spec installs
[`console-guard.ts`](../e2e/tests/helpers/console-guard.ts), which fails the test on any
`pageerror` or `console.error` (network aborts on navigation are ignored) — the only reliable
detector of an aborted attribute pass. `live-counter.spec.ts` opens two browser contexts as two
users and asserts a click in one is pushed into the other over SSE.

## Conventions used in this repository

Tests are inline RCF `(tests …)` blocks (`hyperfiddle.rcf`), run by Kaocha with
`-Dhyperfiddle.rcf.generate-tests=true` (`bb test`). Values are bound with `def` so they can be
inspected in a REPL; assertions are top-level `:=`. Because the dev server enables RCF, a
lazily-loaded component namespace runs its tests on first request — keep test blocks free of
process-global side effects, or restore what they change.
