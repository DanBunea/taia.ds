import { test, expect } from '@playwright/test';
import { collectConsoleErrors } from './helpers/console-guard';

// A connected component with two ways in. The count is the tab's (its
// :memory, round-tripped through data-signals); a click POSTs to the
// nds-connect path and runs through the flow the stream opened. (The other
// way in, a server event — send-event!, e.g. from the REPL — is covered by block 2 of
// examples_test.clj.)
test('live counter: clicks go through the connected flow; the count is the tab\'s', async ({ page }) => {
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
