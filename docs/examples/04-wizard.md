# 4 · Wizard

`wizard.wizard` — [`wizard.clj`](../../bases/examples/src/taia/ds/examples/components/wizard/wizard.clj),
[`wizard_controller.clj`](../../bases/examples/src/taia/ds/examples/components/wizard/wizard_controller.clj).

**What to look at**

- `:initial [:editing :details]` — a nested start. The state is a vector all the way through: in
  ctx, in the store (`["editing","details"]`) and in the render fn, which branches on `(last state)`.
- Inside `:editing`, `:target :address` is relative. `:SUBMIT {:target [:saving]}` leaves the parent
  with a full path.
- `:RESET` and `:INIT` sit in the machine-wide `:on`, so they work from every state; `:INIT {}` is
  the hatch.
- `:saving` has an `:invoke`. `save!` runs synchronously in the same request; its return value goes
  to `:on-done`'s action, and `(throw (ex-info …))` goes to `:on-error`, back to the review step
  with the message in `:memory`. Type `fail` as the name to see it.
- The receipt is `dissoc`ed on RESET, so the spec expects `:receipt nil` in the result — the
  removed-signal null.

**Spec**: block 4 in `examples_test.clj`.
