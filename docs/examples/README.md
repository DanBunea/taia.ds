# Examples

Five components, one concept each, in `bases/examples/src/taia/ds/examples/components/`. Run them:

```shell
bb dev          # http://localhost:7788/
```

Every example has an article here and an executable spec in
[`examples_test.clj`](../../bases/examples/test/taia/ds/examples/examples_test.clj).

| | Shows | Component |
|-|-------|-----------|
| [1 · Counter](01-counter.md) | one-shot events, `:memory` round-trip, the render fn as the single source of markup | `basics.counter` |
| [2 · Live counter](02-live-counter.md) | a connected component: SSE stream, server-owned state, `send-ui!` + `:broadcast` | `live.live-counter` |
| [3 · Signup form](03-signup-form.md) | `data-bind` form signals, guarded transitions, an effects list with `:execute-script` | `signup.signup` |
| [4 · Wizard](04-wizard.md) | hierarchical states, relative targets, machine-wide RESET, `:invoke` with on-done / on-error | `wizard.wizard` |
| [5 · TodoMVC](05-todomvc.md) | query-string params, filters, inline editing, keyboard expressions | `todomvc.todomvc` |

The application around them — routes, middleware, the page shell that mounts a component — is
[`core.clj`](../../bases/examples/src/taia/ds/examples/core.clj) and
[`layout.clj`](../../bases/examples/src/taia/ds/examples/layout.clj). It is the reference for your
own base.
