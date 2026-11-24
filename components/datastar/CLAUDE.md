# datastar brick (`taia.ds.datastar`)

Server-side UI component framework (LiveView-style) over [Datastar](https://data-star.dev/) SSE. **Keep it generic — zero app-specific code in here.** Applications register their own component namespaces via configurable search paths.

## Key namespaces

- `taia.ds.datastar.interface` — `handler-component-event`, `handler-connected-component-connect`, `handler-connected-component-event`, `set-component-search-paths!`, `warm-component-cache!`, `set-observer!`, `process-component-event`, `send-ui!`, `send-event!`, `start-component-flow!` / `run-flow-event!` / `stop-component-flow!`.
- `taia.ds.datastar.adapters.flow` — the component flow: the pipeline as a `clojure.core.async.flow`, `:controller` → `:view`, with an external `:in-chan` and `:out-chan`, and the machine and render fn resolved at construction. Owns the graph and its lifecycle (`start-component-flow!` / `stop-component-flow!`) and running events (`run-event!`, `process-component-event`). A one-shot event gets a per-request flow; a connected component's flow lives with its stream and holds the ctx. Proc loops run on virtual threads. Each proc is its own namespace:
  - `adapters.flow.controller` — the state machine proc (`step`, `proc`) and `merge-held-ctx` (held `:state` wins, client `:memory` merged over).
  - `adapters.flow.view` — the render proc (`step`, `proc`).
  - `adapters.flow.message` — the message contract they share (in/out shapes, `with-reply`, `error` / `error-of`). Errors travel as messages, never to the flow's error-chan.
- `taia.ds.datastar.adapters.handlers.pipeline` — the pipeline STEPS the flow procs compose: resolve component → initial ctx → FSM `taia.ds.state-machines/transition` → removal nils → render. `assoc-initial-ctx-and-state` forwards `:app-config` from the request into ctx.
- `taia.ds.datastar.adapters.handlers.discovery` — dynamically requires `<search-path>.<component-name>` and its `-controller` sibling by naming convention. Search paths are set once at startup via `set-component-search-paths!`.
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
