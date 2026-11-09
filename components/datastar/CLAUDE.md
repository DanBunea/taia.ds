# datastar brick (`taia.ds.datastar`)

Server-side UI component framework (LiveView-style) over [Datastar](https://data-star.dev/) SSE. **Keep it generic — zero app-specific code in here.** Applications register their own component namespaces via configurable search paths.

## Key namespaces

- `taia.ds.datastar.interface` — `handler-component-event`, `handler-connected-component-connect`, `set-component-search-paths!`, `warm-component-cache!`, `set-observer!`, `process-component-event`, `send-ui!`.
- `taia.ds.datastar.adapters.handlers.pipeline` — the request pipeline: resolve component → FSM `taia.ds.state-machines/transition` → render → SSE patch. `assoc-initial-ctx-and-state` forwards `:app-config` from the request into ctx.
- `taia.ds.datastar.adapters.handlers.discovery` — dynamically requires `<search-path>.<component-name>` and its `-controller` sibling by naming convention. Search paths are set once at startup via `set-component-search-paths!`.
- `taia.ds.datastar.adapters.handlers.discovery-cache` — caches the search-path scan per `[component controller]`. It stores the resolved namespace SYMBOLS, never the vars, so REPL redefs (and `remove-ns`) are always picked up; `set-component-search-paths!` clears it. `discovery/warm-cache!` (exposed as `warm-component-cache!`) pre-resolves a list of components on a background thread at startup — otherwise the first request for a component pays the namespace-load cost on the request thread.
- `taia.ds.datastar.adapters.handlers.{context,response,default_component_handler,connected_component_handler}` — ctx assembly and SSE response plumbing.
- `taia.ds.datastar.adapters.handlers.observer` — the optional observer hook (`set-observer!`). Both handlers report component-event timing and SSE open/close as plain maps, with bounded labels (discovered components, machine-declared events) and no component-id. It stays metrics-agnostic: the application plugs in its own metrics sink.
- `taia.ds.datastar.adapters.components.counter.*` — example components (also exercised by the inline tests and the development server).

## Conventions

- Routes: `POST /ds/nds/<component-name>/<component-id>/<event>`; long-lived/connected components use `/ds/nds-connect/...`.
- Component contract: `<name>.clj` pure render fn `(fn [ctx] -> hiccup)`, named after the last namespace segment, + `<name>_controller.clj` declaring `machine`.
- Signals: `:memory` (persistent state) and `:state` (FSM keyword) round-trip as JSON via `data-signals`.
- `send-ui!` queues component responses for async SSE delivery to a (component-id, user-id) pair — the fan-out primitive for server-initiated pushes.
- Static assets: `resources/datastar/js/{datastar.js,sse-reconnect.js}`, served by consumers with `(wrap-resource handler "datastar")` at `/js/...`.
- Tests are inline RCF `(tests …)` blocks; run with `bb test`.
