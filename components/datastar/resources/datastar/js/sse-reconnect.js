// Reconnect watchdog for persistent /ds/nds-connect/ SSE streams.
//
// Datastar (v1 RC8) auto-reconnects a stream that closes cleanly, but gives
// up for good after 10 failed retries ("retries-failed" → "finished"), and
// Safari can kill a stream with NO error at all (tab suspension, HTTP/3
// stalls, BFCache restores) — datastar never notices, so it never retries.
// Any element carrying a `data-on:ds-reconnect` attribute (whose expression
// re-issues the connect @get) opts into this watchdog: once the element has
// started a fetch, the watchdog re-dispatches `ds-reconnect` on it whenever
// its stream dies terminally or goes silent.
(function () {
  var RECONNECT_DELAY_MS = 2000;
  // Server heartbeats every 15s on every open stream; this is two missed
  // heartbeats plus grace.
  var SILENCE_LIMIT_MS = 40000;
  var SELECTOR = '[data-on\\:ds-reconnect]';

  // Per-element fetch generation: bumped on every "started". Lets a delayed
  // reconnect stand down when a newer fetch (datastar retry, user click) has
  // already replaced the one whose death scheduled it. WeakMap keys survive
  // BFCache (page heap is preserved); a full reload starts clean, where the
  // streams are re-established by their normal triggers anyway.
  var generations = new WeakMap();
  var lastSse = Date.now();

  function marked(el) {
    return el instanceof Element && el.hasAttribute('data-on:ds-reconnect');
  }

  function reconnect(el) {
    if (el && el.isConnected && marked(el)) {
      el.dispatchEvent(new CustomEvent('ds-reconnect'));
    }
  }

  function reconnectAll() {
    document.querySelectorAll(SELECTOR).forEach(function (el) {
      if (generations.has(el)) reconnect(el);
    });
  }

  document.addEventListener('datastar-fetch', function (evt) {
    lastSse = Date.now();
    var detail = evt.detail || {};
    var el = detail.el;
    if (!marked(el)) return;
    if (detail.type === 'started') {
      generations.set(el, (generations.get(el) || 0) + 1);
      return;
    }
    // "error"/"retrying" (and clean closes, which dispatch nothing) are
    // handled by datastar's own retry loop; only a terminal settling of the
    // whole action needs us. "finished" also fires when a new action on the
    // same element aborts the old one — the generation check below stands
    // down when that (or any newer fetch) has already taken over.
    if (detail.type === 'finished' || detail.type === 'retries-failed') {
      var gen = generations.get(el) || 0;
      setTimeout(function () {
        if ((generations.get(el) || 0) === gen) reconnect(el);
      }, RECONNECT_DELAY_MS);
    }
  });

  // Any signal patch (heartbeats included) proves some stream is alive.
  document.addEventListener('datastar-signal-patch', function () {
    lastSse = Date.now();
  });

  // BFCache restore: the DOM comes back intact but every fetch is dead.
  window.addEventListener('pageshow', function (evt) {
    if (evt.persisted) reconnectAll();
  });

  // Tab re-focus after a Safari suspension: the stream can be silently dead
  // (stalled, no error — datastar never retries). Give live streams a moment
  // to flush queued events, then treat prolonged signal silence as death.
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) return;
    setTimeout(function () {
      if (!document.hidden && Date.now() - lastSse > SILENCE_LIMIT_MS) {
        reconnectAll();
      }
    }, 3000);
  });
})();
