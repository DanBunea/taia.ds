# {{main}}

A web application on [taia.ds](https://github.com/DanBunea/taia.ds): server-rendered UI
components over [Datastar](https://data-star.dev/) SSE, in a [Polylith](https://polylith.gitbook.io/)
workspace. Generated from the `taia/app` template.

## Run it

```shell
bb dev
```

This starts the app on http://localhost:{{port}} and an nREPL on {{nrepl-port}}. It has two
components to start from:

- **Counter** (`basics.counter`): one-shot events. The count lives in `:memory` and
  round-trips through the page's signals.
- **Live counter** (`live.live-counter`): a connected component. A click and a server event
  go through the same flow.

Edit a render fn or a machine, re-evaluate the namespace, and click again. Components need no
restart. Restart only after changing routes or middleware.

## Tasks

```shell
bb dev        # app on {{port}} + nREPL on {{nrepl-port}}
bb test       # Kaocha + RCF tests, inline in src and in bases/web/test
bb e2e-test   # Playwright specs against the app on {{e2e-port}}; bb e2e-test <spec> [--headed]
bb check      # poly check
bb info       # poly info
bb uberjar    # projects/{{main}}/target/{{main}}.jar
bb run-server # java -jar that jar (PORT env var, default {{port}})
bb format     # cljfmt fix (needs cljfmt on the PATH)
```

## Tests

There are three layers, each catching what the one before can't:

- **Inline `(tests …)` blocks** sit next to the code (RCF), one function per test:
  - each controller's actions on their own;
  - its machine, with `test-machine`: per state and event, where it lands and the trace of
    actions it runs (without running them);
  - each render fn, with its collaborators (`ds/ctx->data-signals`, `inspect/state-pre`) spied.
- **`web_test.clj`** drives the whole pipeline in process (discovery → machine → render) through
  `test_support.clj`, with no HTTP: the round trip of `:memory` through the signal store, and a
  connected component's server events.
- **`e2e/`** has Playwright specs against the running app, all under `console-guard.ts`, which fails
  a spec on any browser console error. This is the only way to see a Datastar expression that throws
  and silently stops the attribute pass for the whole patched subtree, which no server-side test
  notices. `bb e2e-test` needs Node >= 18 and downloads Chromium on its first run.

## The REPL

Connect to the nREPL that `bb dev` opens, or start your own REPL with the `:dev` alias and call
`(start!)`:

```clojure
(start!)     ; the app on {{port}}; returns its URL
(restart!)   ; after changing routes or middleware

;; open the live counter page (you are "demo-user"), then send its flow a
;; server event: component-id, user-id, event, params. A server event has no
;; signals, so it sends the memory as params.
(require '[taia.ds.datastar.interface :as ds])
(ds/connections)                                            ; {"live1" ["demo-user"]}
(ds/send-event! "live1" "demo-user" "increment" {:count 2}) ; the tab shows 3
(ds/send-event! "live1" "demo-user" "reset" {})             ; back to 0
```

`development/src/dev.clj` and the live counter's controller have these in their `comment` blocks,
and `dev.clj` also shows `ds/send-ui!`.

## Layout

```
bases/web/src/{{top/file}}/{{main/file}}/web/
  core.clj                       routes, middleware, async Jetty, -main
  inspect.clj                    the state + memory viewer under each component
  components/home/               the page itself (a component) + the registry
  components/basics/counter*.clj
  components/live/live_counter*.clj
bases/web/test/…                 test_support.clj (pipeline in process) + web_test.clj
e2e/                             Playwright: one spec per component, console-guard.ts
scripts/e2e.clj                  bb e2e-test: starts the app on {{e2e-port}}, runs the specs
development/src/                 dev.clj (start!/stop!/restart!, nREPL), user.clj
projects/{{main}}/               the deployable project: deps.edn + build.clj (uberjar)
components/                      your Polylith components, as the app grows
```

## Add a component

1. Write `components/<group>/<name>.clj`, a render fn named `<name>` that returns hiccup whose
   root has `:id component-id`.
2. Write `components/<group>/<name>_controller.clj`, which defines a `machine`.
3. Add an entry to `examples` in `components/home/home_controller.clj`, with `:component
   "<group>.<name>"` and a `:cid`. Add `:connected? true` for a component that keeps a stream
   open.
4. Add tests: inline `(tests …)` for the actions, the machine and the render fn; a block in
   `web_test.clj`; and an `e2e/tests/<name>.spec.ts` that calls `installConsoleGuard()`.

The concepts are in the taia.ds docs: [components](https://github.com/DanBunea/taia.ds/blob/main/docs/components.md),
[state machines](https://github.com/DanBunea/taia.ds/blob/main/docs/state-machines.md),
[connected components](https://github.com/DanBunea/taia.ds/blob/main/docs/connected-components.md),
[testing](https://github.com/DanBunea/taia.ds/blob/main/docs/testing.md), and the
[gotchas](https://github.com/DanBunea/taia.ds/blob/main/docs/gotchas.md). The
[examples](https://github.com/DanBunea/taia.ds/tree/main/docs/examples) go further: forms,
wizards, TodoMVC, invokes, and server-driven flows.

## Before production

- **Auth:** `wrap-logged-user` in `core.clj` takes the user from a `?user=` query param. That is a
  stand-in. Replace it with your authentication, keeping `[:params :logged-user :user/id]`, because
  SSE connections are keyed by it.
- **Upgrading taia.ds:** bump `io.github.danbunea/taia.ds` in both `deps.edn` and
  `projects/{{main}}/deps.edn`.
