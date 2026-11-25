# API index

> GENERATED — do not edit. Regenerate with `bb api-index`.
> One section per brick interface namespace (and its `interface.*` sub-namespaces) and its public fns/defs. Everything outside these namespaces is internal and may change without notice.

## `datastar` — `taia.ds.datastar.interface`

[components/datastar/src/taia/ds/datastar/interface.cljc](../components/datastar/src/taia/ds/datastar/interface.cljc)

- `handler-component-event (def)` — Ring handler for dynamic component events (/ds/nds/<component>/<component-id>/<event>):
- `handler-connected-component-connect (def)` — Async Ring handler for a connected component's path
- `handler-connected-component-event (def)` — Deprecated: mount `handler-connected-component-connect` alone, on any
- `set-observer! [f]` — Installs `f` as the observer of component events on /ds/nds and
- `set-component-search-paths! [paths]` — Sets the ordered list of namespace prefixes used to resolve components.
- `warm-component-cache! [component-names]` — Pre-resolves the given Datastar components into the discovery cache so the
- `process-component-event [req component-name component-id event-name controller-name]` — Runs one event through a per-request component flow in-process
- `send-ui! [component-id user-id component-responses]` — Puts component-responses onto the UI channel for async delivery to
- `start-component-flow! [opts]` — Starts a component's flow headless — no SSE stream: the machine and
- `run-flow-event! [component-flow req event-name] [component-flow req event-name params]` — Runs one event through a flow from `start-component-flow!` and returns
- `stop-component-flow! [component-flow]` — Stops a flow from `start-component-flow!`.
- `connections []` — The open SSE streams, as {component-id [user-id …]}: who `send-ui!` and
- `send-event! [component-id user-id event-name params]` — Sends a server event to the connected component (component-id, user-id):
- `ctx->data-signals [ctx]` — The `data-signals` attribute value for a component's root element: the
- `html-response [hiccup]` — Ring 200 text/html response for `hiccup` — e.g. the full-page render that

## `extensions` — `taia.ds.extensions.interface`

[components/extensions/src/taia/ds/extensions/interface.cljc](../components/extensions/src/taia/ds/extensions/interface.cljc)

- `assoc-if [o p v when?]` — Assocs k->v onto o only when when? is truthy; otherwise returns o unchanged.
- `assoc-existing [o p v]` — Assocs k->v onto o only when key p already has a truthy value in o.
- `update-if [o p v when?]` — Updates key p in o with fn v only when when? is truthy; otherwise returns o unchanged.
- `update-in-if [o p v when?]` — Updates path p in o with fn v only when when? is truthy; otherwise returns o unchanged.
- `update-existing [o p v]` — Updates key p in o with fn v only when p already has a truthy value in o.
- `update-in-existing [o p v]` — Updates path p in o with fn v only when the path already has a truthy value in o.

## `logging` — `taia.ds.logging.interface`

[components/logging/src/taia/ds/logging/interface.cljc](../components/logging/src/taia/ds/logging/interface.cljc)

- `set-log-level! [level]` — Sets the global log level. Called once at startup from config.
- `log-level []` — Returns the current global log level.
- `trace-enabled? [level min-level]` — Returns true when trace at level should be emitted given min-level.
- `with-context [ctx & body]` — Macro wrapper for mulog/with-context.
- `trace-when [condition event-name pairs & body]` — When condition is true, runs (u/trace event-name pairs body).
- `trace [event-name pairs & body]` — Always runs (u/trace event-name pairs body).
- `warn [event-name pairs]` — Emits a discrete mulog event at warn severity. Unlike `trace`, this is
- `local-context []` — Function wrapper for mulog/local-context.
- `flatten-ctx [ctx]` — Returns a mulog context that re-parents a span directly under the turn's
- `safe-for-log [v]` — Returns a single log-safe **string** representation of v, suitable as

## `state-machines` — `taia.ds.state-machines.interface`

[components/state-machines/src/taia/ds/state_machines/interface.cljc](../components/state-machines/src/taia/ds/state_machines/interface.cljc)

- `transition [machine :- domain/MachineDefinition from-state :- domain/Context [event-name event-params] :- domain/Event] [machine :- domain/MachineDefinition from-state :- domain/Context [event-name event-params] :- domain/Event opts :- domain/TransitionOpts] :- domain/Context` — Runs a state machine transition. Returns the updated context map containing
- `make-machine-testable (def)` — Replaces named fn values in a machine with their vars so with-redefs can intercept them in tests.

## `state-machines` — `taia.ds.state-machines.interface.test-helpers`

[components/state-machines/src/taia/ds/state_machines/interface/test_helpers.cljc](../components/state-machines/src/taia/ds/state_machines/interface/test_helpers.cljc)

- `test-machine (def)` — Declarative machine test: per state and event, the target and the trace of actions/invokes.
