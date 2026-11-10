# Changelog

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
