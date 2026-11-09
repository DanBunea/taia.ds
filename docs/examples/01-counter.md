# 1 · Counter

`basics.counter` — [`counter.clj`](../../bases/examples/src/taia/ds/examples/components/basics/counter.clj),
[`counter_controller.clj`](../../bases/examples/src/taia/ds/examples/components/basics/counter_controller.clj).

The smallest complete component: a render fn and a one-state machine.

**What to look at**

- The root element carries `:id component-id` and `:data-signals (ds/ctx->data-signals ctx)`. The
  first makes the morph land; the second is the whole persistence story — `{:count 2}` lives in the
  browser and comes back with every click.
- Each button is `"data-on:click" "@post('/ds/nds/basics.counter/counter1/increment')"`. The event
  name in the URL is lower-case; the machine sees `:INCREMENT`.
- `INIT` does `(update-in ctx [:memory :count] #(or % 0))`, not `assoc 0`: a count already in the
  store survives a second INIT.
- `{:actions f}` without `:target` stays in `:displayed`.

**Try it**: click, then reload the page — the count is gone. One-shot components own no server state.

**Spec**: the first `tests` block in `examples_test.clj` (INIT seeds 0, increments accumulate
through the store, every button posts to this component's own route).
