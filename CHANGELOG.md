# Changelog

## 0.1.x — 2026-11-09

- Extracted from `tbc.polylith` (commit `6e93fd4a`) as a standalone Polylith workspace.
- Bricks: `datastar`, `state-machines`, `logging`, `extensions`, renamed under `taia.ds.*`.
- `datastar.js` (v1.0.0-RC.8) and `sse-reconnect.js` shipped as classpath resources under `datastar/js/`.
- `examples` base + `projects/examples`: five components (counter, live counter, signup form, wizard, TodoMVC) with walkthroughs in `docs/examples/` and an in-process test suite; `bb dev` serves them.
- Docs: getting started, components, state machines, connected components, testing, gotchas, migration; `doc/cljdoc.edn`; generated `docs/api.md` (`bb api-index`).
- Interface additions: `ctx->data-signals`, `html-response`.
- Connected counter example renders its initial state as a `:patch` (the previous `:broadcast` effect was ignored by the initial-render path, so the example never rendered).
- Example page head is generic (no application stylesheets); discovery tests no longer depend on application components.
