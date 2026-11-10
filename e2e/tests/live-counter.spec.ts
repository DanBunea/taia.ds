import { test, expect } from '@playwright/test';
import { collectConsoleErrors } from './helpers/console-guard';

// Two browser contexts = two users, each with its own SSE stream. A click in
// alice's tab must change bob's page with no action on bob's side: that is
// send-ui! + :broadcast fanning out over /ds/nds-connect/.
test('live counter: a change in one user\'s tab is pushed to the other', async ({ browser }) => {
  const alice = await (await browser.newContext()).newPage();
  const bob = await (await browser.newContext()).newPage();
  const aliceErrors = collectConsoleErrors(alice);
  const bobErrors = collectConsoleErrors(bob);

  await alice.goto('/ds/nds/home.home/home/init?example=live-counter&user=alice');
  await bob.goto('/ds/nds/home.home/home/init?example=live-counter&user=bob');
  // the count arrives over the stream (INIT render), so visible == connected
  await expect(alice.getByTestId('count')).toBeVisible();
  await expect(bob.getByTestId('count')).toBeVisible();
  await expect(bob.getByText('signed in as bob')).toBeVisible();

  const before = Number(await bob.getByTestId('count').textContent());

  await alice.getByTestId('inc').click();
  await expect(bob.getByTestId('count')).toHaveText(String(before + 1));
  await expect(alice.getByTestId('count')).toHaveText(String(before + 1));

  await alice.getByTestId('inc').click();
  await expect(bob.getByTestId('count')).toHaveText(String(before + 2));

  // and the other way round
  await bob.getByRole('button', { name: 'Reset' }).click();
  await expect(alice.getByTestId('count')).toHaveText('0');

  expect(aliceErrors(), 'alice console').toEqual([]);
  expect(bobErrors(), 'bob console').toEqual([]);
  await alice.context().close();
  await bob.context().close();
});
