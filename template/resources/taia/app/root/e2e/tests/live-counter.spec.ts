import { test, expect } from '@playwright/test';
import { collectConsoleErrors } from './helpers/console-guard';

// A connected component with two ways in. The count is the tab's (its
// :memory, round-tripped through data-signals); a click is a one-shot POST
// to /ds/nds/, and the nds-connect stream stays open for server events. (The other
// way in, a server event — send-event!, e.g. from the REPL — is covered by the
// live counter block of web_test.clj.)
test('live counter: one-shot clicks next to an open stream; the count is the tab\'s', async ({ page }) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/ds/nds/home.home/home/init?example=live-counter&user=alice');
  // the count arrives over the stream (INIT render), so visible == connected
  await expect(page.getByTestId('count')).toHaveText('0');

  await page.getByTestId('inc').click();
  await expect(page.getByTestId('count')).toHaveText('1');
  await page.getByTestId('inc').click();
  await expect(page.getByTestId('count')).toHaveText('2');

  await page.getByTestId('reset').click();
  await expect(page.getByTestId('count')).toHaveText('0');

  expect(errors(), 'console').toEqual([]);
});

// Nothing is kept on the server: each tab counts on its own.
test('live counter: two users count independently', async ({ browser }) => {
  const alice = await (await browser.newContext()).newPage();
  const bob = await (await browser.newContext()).newPage();
  const aliceErrors = collectConsoleErrors(alice);
  const bobErrors = collectConsoleErrors(bob);

  await alice.goto('/ds/nds/home.home/home/init?example=live-counter&user=alice');
  await bob.goto('/ds/nds/home.home/home/init?example=live-counter&user=bob');
  await expect(alice.getByTestId('count')).toHaveText('0');
  await expect(bob.getByTestId('count')).toHaveText('0');

  await alice.getByTestId('inc').click();
  await alice.getByTestId('inc').click();
  await expect(alice.getByTestId('count')).toHaveText('2');
  await expect(bob.getByTestId('count')).toHaveText('0');

  expect(aliceErrors(), 'alice console').toEqual([]);
  expect(bobErrors(), 'bob console').toEqual([]);
  await alice.context().close();
  await bob.context().close();
});

// The reconnect watchdog (sse-reconnect.js) re-dispatches ds-reconnect on the
// element that opened the connection. The marker sits on a wrapper: on #live1
// itself the first render's morph would drop it. A reconnect is a new
// connection whose INIT starts from the tab's signals, so the count stays.
test('live counter: ds-reconnect on the mount re-opens the connection; the count survives', async ({ page }) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/ds/nds/home.home/home/init?example=live-counter&user=carol');
  await expect(page.getByTestId('count')).toHaveText('0');
  await expect(page.locator('#live1-mount')).toHaveAttribute('data-on:ds-reconnect', /nds-connect/);

  await page.getByTestId('inc').click();
  await expect(page.getByTestId('count')).toHaveText('1');

  const reopened = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().includes('/ds/nds-connect/live.live-counter/live1/init'),
  );
  await page.locator('#live1-mount').dispatchEvent('ds-reconnect');
  await reopened;
  await expect(page.getByTestId('count')).toHaveText('1');

  await page.getByTestId('inc').click();
  await expect(page.getByTestId('count')).toHaveText('2');

  expect(errors(), 'console').toEqual([]);
});
