# Changelog

## Unreleased

### Upgrading from 0.3.x

- **Move a connected component's clicks to `/ds/nds/…`.** A request on `/ds/nds-connect/…` now always opens a connection, whatever its method and event. A click POSTed there would open a stream of its own on the button, and that stream would replace the page's connection as the one `send-event!` reaches.
- **`handler-connected-component-event` is removed.** Mount the connect handler once, on any method, and drop the `:post` route (a route map that still names it fails to compile, which points at the clicks to move):
  ```clojure
  ["/nds-connect"
   ["/:component/:component-id/:event" {:handler ds/handler-connected-component-connect}]]
  ```

### Connected components

- `/ds/nds-connect/…` has one job: opening a connection for `send-event!`. Every request there, on any method (`@get` or `@post`) and with any event, answers with an SSE stream, starts a new flow, runs its event through it, and registers stream and flow under (component-id, user), replacing and stopping whatever was there. `send-event!` runs server events through that flow, one after the other.
- Browser events don't go through a connection's flow any more: nothing is kept between events, so a one-shot flow renders the same. The POST-into-the-flow path of 0.3.0 is gone (`handler-connected-component-event`), and with it the observer's `:nds-connect-event` route.
- Behaviour that does not change, now written down in [Connected components](docs/connected-components.md#the-reconnect-watchdog): a reconnect is a new connection and a new flow; server events sent while a stream is down are dropped, not queued; there is one connection per (component-id, user), not per tab.
- Docstrings and docs no longer say a connected flow holds the ctx (it has kept nothing between events since 0.3.0).

### Examples and template

- The live counter's and the countdown's clicks are one-shot POSTs to `/ds/nds/…`; the examples app and the template mount nds-connect once, on any method. Both work on 0.3.2 as they are, so the template keeps its pin.
- The home page mounts a child in a wrapper (`#<cid>-mount`) around `#<cid>`, and a connected child's mount POSTs its INIT and carries `data-on:ds-reconnect`, so the reconnect watchdog re-opens a dropped connection. The marker cannot sit on `#<cid>` itself: the render's morph drops attributes it does not produce ([Gotchas](docs/gotchas.md)). The watchdog example in [Connected components](docs/connected-components.md#the-reconnect-watchdog) had that mistake; it now uses a wrapper.
- README animation: the click on the live counter runs in a flow of its own; the REPL's `send-event!` goes through the connection's flow.

## 0.3.2 — 2026-11-24

- A deps-new template, `taia/app`. `clojure -Tnew create :template io.github.danbunea/taia.ds%template%taia/app :name acme/shop` generates a Polylith workspace with async Jetty, reitit, `bb dev` with an nREPL, RCF tests, `poly check`, an uberjar (`bb uberjar`), and the counter and live counter ready to edit. See `docs/new-project.md`. `bb template-test` generates a project and runs its checks. The generated project also has inline RCF tests for each action, machine and render fn, and Playwright specs for both components under the console guard (`bb e2e-test`).
- `taia.ds.state-machines.interface.test-helpers/test-machine`: the declarative machine runner on the interface, so a base in a Polylith workspace that has the brick can use it (`poly check` rejects `taia.ds.state-machines.test-helpers` there). `taia.ds.state-machines.test-helpers` stays, with the same fn.
- Examples: the counter and live counter carry inline tests: each controller action, the machine with `test-machine` (target and action trace per state and event), and the render fn with its collaborators spied. `inspect/state-pre` is tested too. The live counter's REPL helpers `inc!` / `reset!` are gone: a server event from the REPL is `ds/send-event!` itself.

## 0.3.1 — 2026-11-24

A docs release: the library code is the same as 0.3.0.

- cljdoc: `doc/cljdoc.edn` sets `:cljdoc/languages ["clj"]`. The library is JVM-only, and cljdoc's ClojureScript analysis of the `.cljc` interface (which requires `.clj` handlers) failed the whole API import, so 0.2.0 and 0.3.0 have articles but no API pages on cljdoc. 0.3.1 is the first with them.
- Examples: the live counter keeps its count in the tab's `:memory` (no server atom, no `send-ui!` broadcast). A click carries it in the signals; a server event — `(ds/send-event! "live1" user "increment" {:count n})`, e.g. from the REPL — sends it as params. The controller's REPL helpers `inc!` / `reset!` now take and send the count too. Its e2e spec now checks two users counting independently.
- README animations: the first no longer has a `send-event!` step (the second covers it); the second shows the REPL sending the memory as params, with no server state.

## 0.3.0 — 2026-11-24

### Upgrading from 0.2.0

- **Mount the new POST route on the connected path** to send a connected component's clicks through its flow (optional; nothing breaks without it):
  ```clojure
  ["/nds-connect"
   ["/:component/:component-id/:event" {:get  {:handler ds/handler-connected-component-connect}
                                        :post {:handler ds/handler-connected-component-event}}]]
  ```
- **`process-component-event`'s result no longer carries `:request`.** It keeps its signature and still returns `:ctx` and `:html`; it now throws an `ex-info` with `::timeout` after 30 s.
- **A server event starts from the machine's initial state** — nothing is kept between events — so send what its action needs as params (`(ds/send-event! cid user "tick" {:remaining 4})`), and handle an event that can arrive in any state on the machine's own `:on` ([Gotchas](docs/gotchas.md)).

### The component flow

- Every event runs through a `clojure.core.async.flow` of two procs, `:controller` (the state machine) and `:view` (the render fn), between an `:in-chan` and an `:out-chan`. The machine and render fn are resolved once, when the flow is built.
- The code is layered: the use case `taia.ds.datastar.use-cases.process-event` (with `process-event.controller`, `.view` and `.common`), the pure rules in `domain.ctx` and `domain.event`, and a new adapter, `adapters.handlers.request`, that turns a Ring request into the flow's message — the use case and the domain see plain data only. `pipeline.clj` is gone.
- A one-shot event (`/ds/nds/…`) gets a flow of its own: about 200 µs per event against 100 µs for the plain pipeline, with no thread or heap growth (proc loops run on virtual threads).

### Connected components

- A connected component's flow lives as long as its SSE stream; clicks and server events go in through the same in-chan. Nothing is kept between events: a click starts from the tab's signals, a server event from the machine's initial state and its params, and whatever an action adds for a render is gone once it is sent (no per-connection memory).
- New `handler-connected-component-event`: `POST /ds/nds-connect/<component>/<component-id>/<event>` runs through the stream's flow and is answered with the render; without an open stream it falls back to the one-shot handler.
- New `send-event!`: a server event to a connection's flow; its render is pushed down the stream. New `connections`: who has an open stream, `{component-id [user-id …]}` — look there when `send-event!` answers false.
- New `start-component-flow!`, `run-flow-event!` and `stop-component-flow!`: drive a flow headless, e.g. to test a connected component in process.
- `!connections` entries are `{:sse-gen :flow}`; whoever removes an entry (on-close, a stale write, a reconnect) stops its flow.
- Observer: a POST through a connected flow reports `:route :nds-connect-event`.

### Examples

- 7 · Countdown (`live.countdown`): a connected component driven by server events, with its article, spec block and e2e spec.
- The live counter's clicks POST to the nds-connect path, so a click and a server event go through the same in-chan.
- REPL helpers in the connected examples' comment blocks: the live counter's `(inc!)` / `(reset!)`, and `send-event!` / `connections` snippets.

### Development

- `dev`: `start!` / `stop!` / `restart!` touch only the HTTP server, never the nREPL you are connected through; a lazy `user` ns offers the same; Jetty logs at WARN in development.
- `bb coverage`: the unit tests under Cloverage, an HTML report in `target/coverage/`; `bb coverage <ns-prefix>` reports only those namespaces.
- The tests follow one style throughout ([Testing](docs/testing.md#how-the-tests-are-written)): one function per test, collaborators spied with `spy/called-once-with?`, values written inline, threads one step per line. 114 test blocks, 748 assertions.

### Docs

- README: two animated diagrams (a click through the component flow; a click and a REPL event through a connected component's one in-chan), generated by `scripts/component_flow_svg.clj` (`bb component-flow-gif`), and a "What you write" section.
- Connected components: the flow, `send-event!` with params, the POST route, and what a server event starts from.

## 0.2.0 — 2026-11-10

- Examples: the examples home page is now itself a component (`home.home`, with a controller). There is no page route any more: a GET on `/ds/nds/home.home/home/init?example=<name>` renders it, and `start!` returns that URL. `layout.clj` → `components/home/` (the registry lives in the controller).
- README: "If you know re-frame" and "If you know XState" sections; a Mermaid state diagram in every example walkthrough and in `docs/state-machines.md`.
- The `datastar` brick no longer ships example components: `adapters.components.counter.*` and `html-page` are gone from the jar. The discovery tests moved to the brick's `test` dir and define the component they resolve in memory.
- The default component search path is now `[]` (was `["taia.ds.datastar.adapters.components"]`, which named nothing). Applications must call `set-component-search-paths!` at startup — they already had to.
- `logging`: the REPL debugging helpers `debug`, `log->`, `log->>` and `dbg` are gone; `levels` is private. The interface is `trace`, `trace-when`, `trace-enabled?`, `warn`, `with-context`, `local-context`, `flatten-ctx`, `log-level`, `set-log-level!`, `safe-for-log`.

## 0.1.0 — 2026-11-09

- Extracted from the application it was developed in, as a standalone Polylith workspace.
- Bricks: `datastar`, `state-machines`, `logging`, `extensions`, renamed under `taia.ds.*`.
- `datastar.js` (v1.0.0-RC.8) and `sse-reconnect.js` shipped as classpath resources under `datastar/js/`.
- `examples` base + `projects/examples`: six components (counter, live counter, signup form, wizard, TodoMVC, weather via Open-Meteo) with walkthroughs in `docs/examples/` and an in-process test suite; `bb dev` serves them.
- `bb e2e-test`: Playwright suite against the examples app (`e2e/`), with a console-error guard and a two-user SSE fan-out spec.
- README animation of one counter event end to end (`docs/img/counter-flow.svg`, GIF rendered by `bb flow-gif`).
- Docs: getting started, components, state machines, connected components, testing, gotchas, migration; `doc/cljdoc.edn`; generated `docs/api.md` (`bb api-index`).
- Interface additions: `ctx->data-signals`, `html-response`.
- Connected counter example renders its initial state as a `:patch` (the previous `:broadcast` effect was ignored by the initial-render path, so the example never rendered).
- Example page head is generic (no application stylesheets); discovery tests no longer depend on application components.
