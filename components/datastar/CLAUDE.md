# datastar brick (`taia.ds.datastar`)

Server-side UI component framework (LiveView-style) over [Datastar](https://data-star.dev/) SSE. **Keep it generic — zero app-specific code in here.** Applications register their own component namespaces via configurable search paths.

## Key namespaces

- `taia.ds.datastar.interface` — `handler-component-event`, `handler-connected-component-connect`, `handler-connected-component-event`, `set-component-search-paths!`, `warm-component-cache!`, `set-observer!`, `process-component-event`, `send-ui!`, `send-event!`, `start-component-flow!` / `run-flow-event!` / `stop-component-flow!`.
- `taia.ds.datastar.use-cases.process-event` — the use case: processing a component's event, run as a `clojure.core.async.flow` (`:controller` → `:view`, an external `:in-chan` and `:out-chan`). Plain data only: a resolved component (`{:machine-var :component-fn-var}` + `:component-name`/`:component-id`) and event messages — never a Ring request, never discovery. `start-flow!` / `stop-flow!`, `run-event!`, `await-render`, `process-event` (a one-shot event in a flow of its own). A connected component's flow lives with its stream; nothing is kept between events (a click starts from its signals, a server event from the machine's initial state and its params). Proc loops run on virtual threads. Its namespaces:
  - `process-event.controller` — the state machine proc (`step`, `proc`) and its tracing hooks.
  - `process-event.view` — the render proc (`step`, `proc`, `render`).
  - `process-event.common` — the message contract (in/out shapes, `server-event`, `with-reply`, `error` / `error-of`). Errors travel as messages, never to the flow's error-chan.
- `taia.ds.datastar.domain.ctx` / `domain.event` — the pure rules: the ctx from the client's signals (`initial-ctx`), removal nils (`with-removal-nils`); the transition (`event/transition`, Datastar's internal events skipped).
- `taia.ds.datastar.adapters.handlers.request` — Ring request → event message (the params, the component's signal slice from the JSON body or the GET `datastar` param, `:logged-user` / `:app-config` / `:request-method`). The only place that knows a request's shape.
- Dependencies point inward: adapters (handlers, `request`, `discovery`) → the use case → the domain. `default-component-handler/process-component-event` is the adapter-level one-shot entry (resolve → `->event-msg` → `process-event`), re-exported on the interface.
- `taia.ds.datastar.adapters.handlers.discovery` — `resolve-component` → `{:machine-var :component-fn-var}`; dynamically requires `<search-path>.<component-name>` and its `-controller` sibling by naming convention. Search paths are set once at startup via `set-component-search-paths!`.
- `taia.ds.datastar.adapters.handlers.discovery-cache` — caches the search-path scan per `[component controller]`. It stores the resolved namespace SYMBOLS, never the vars, so REPL redefs (and `remove-ns`) are always picked up; `set-component-search-paths!` clears it. `discovery/warm-cache!` (exposed as `warm-component-cache!`) pre-resolves a list of components on a background thread at startup — otherwise the first request for a component pays the namespace-load cost on the request thread.
- `taia.ds.datastar.adapters.handlers.{context,response,default_component_handler,connected_component_handler}` — ctx assembly and SSE response plumbing.
- `taia.ds.datastar.adapters.handlers.observer` — the optional observer hook (`set-observer!`). Both handlers report component-event timing and SSE open/close as plain maps, with bounded labels (discovered components, machine-declared events) and no component-id. It stays metrics-agnostic: the application plugs in its own metrics sink.
- The brick ships NO example components and no fixture files. `test/…/handlers/discovery_test.clj` defines the component its discovery tests resolve in memory (`install-fixture!`); the examples live in `bases/examples`.

## Conventions

- Routes: `POST /ds/nds/<component-name>/<component-id>/<event>`; long-lived/connected components use `GET /ds/nds-connect/...` (open the stream) and `POST /ds/nds-connect/...` (an event through the stream's flow).
- Component contract: `<name>.clj` pure render fn `(fn [ctx] -> hiccup)`, named after the last namespace segment, + `<name>_controller.clj` declaring `machine`.
- Signals: `:memory` (persistent state) and `:state` (FSM keyword) round-trip as JSON via `data-signals`.
- `send-ui!` queues component responses for async SSE delivery to a (component-id, user-id) pair — the fan-out primitive for server-initiated pushes. `send-event!` sends an EVENT to that connection's flow instead (rendered by the component's own render fn).
- `!connections` is `{component-id {user-id {:sse-gen :flow}}}`; whoever removes an entry (on-close, stale write, reconnect) stops its flow.
- Static assets: `resources/datastar/js/{datastar.js,sse-reconnect.js}`, served by consumers with `(wrap-resource handler "datastar")` at `/js/...`.
- Tests are inline RCF `(tests …)` blocks; run with `bb test`.
