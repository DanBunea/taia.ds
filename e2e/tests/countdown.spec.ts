import { test, expect } from '@playwright/test';
import { collectConsoleErrors } from './helpers/console-guard';

// After one click the SERVER drives it through the component's flow:
// every TICK is a send-event! rendered by the flow and pushed down the stream.
test('countdown: one click, then server events count down to 0', async ({ page }) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/ds/nds/home.home/home/init?example=countdown&user=carol');
  // the INIT render arrives over the stream, so visible == connected
  await expect(page.getByTestId('remaining')).toHaveText('5');

  // the POST runs through the same flow and answers with the :running render
  await page.getByTestId('start').click();
  await expect(page.getByTestId('start')).toBeDisabled();

  // no further clicks: the ticks are server events
  await expect(page.getByTestId('remaining')).toHaveText('3', { timeout: 10_000 });
  await expect(page.getByTestId('remaining')).toHaveText('0', { timeout: 10_000 });
  await expect(page.getByTestId('phase')).toContainText('server sent every tick');
  await expect(page.getByTestId('start')).toHaveText('Again');

  expect(errors(), 'console').toEqual([]);
});

// Each tab (user) has its own stream and therefore its own flow and state.
test('countdown: two users run independent flows', async ({ browser }) => {
  const alice = await (await browser.newContext()).newPage();
  const bob = await (await browser.newContext()).newPage();
  const aliceErrors = collectConsoleErrors(alice);
  const bobErrors = collectConsoleErrors(bob);

  await alice.goto('/ds/nds/home.home/home/init?example=countdown&user=alice');
  await bob.goto('/ds/nds/home.home/home/init?example=countdown&user=bob');
  await expect(alice.getByTestId('remaining')).toHaveText('5');
  await expect(bob.getByTestId('remaining')).toHaveText('5');

  await alice.getByTestId('start').click();
  await expect(alice.getByTestId('remaining')).toHaveText('3', { timeout: 10_000 });
  // bob never pressed Start
  await expect(bob.getByTestId('remaining')).toHaveText('5');
  await expect(bob.getByTestId('start')).toBeEnabled();

  expect(aliceErrors(), 'alice console').toEqual([]);
  expect(bobErrors(), 'bob console').toEqual([]);
  await alice.context().close();
  await bob.context().close();
});
