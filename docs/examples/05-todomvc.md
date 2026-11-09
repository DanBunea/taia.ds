# 5 · TodoMVC

`todomvc.todomvc` — [`todomvc.clj`](../../bases/examples/src/taia/ds/examples/components/todomvc/todomvc.clj),
[`todomvc_controller.clj`](../../bases/examples/src/taia/ds/examples/components/todomvc/todomvc_controller.clj).

Everything so far, together, in `:memory` only.

**What to look at**

- Event params on the query string: `toggle?id=3`, `filter?filter=active`. They arrive as
  **strings** in the action's second argument (`param-id` parses them); unknown values fall back.
- Bound inputs use camelCase keys (`newTodo`, `editText`) so `$todos1.newTodo` is a valid
  identifier in Datastar expressions.
- Keyboard: `"data-on:keydown" "evt.key==='Enter' && @post(…); evt.key==='Escape' && @post(…)"`.
- `editingId` is `nil` when idle — a JSON `null` in the store, back to nil on the next request.
- One render fn draws the input, the list, the editing row and the footer from `:memory`; the
  machine is one state with ten actions, each a small pure function over `:memory`.

**Spec**: block 5 in `examples_test.clj` (add trims and clears, params are parsed, filters,
toggle-all, clear-completed, editing round-trip, blank save deletes).
