import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';

installConsoleGuard();

test('counter: one-shot events round-trip :memory; a reload starts over', async ({ page }) => {
  await page.goto('/ds/nds/home.home/home/init?example=counter');
  const count = page.getByTestId('count');
  await expect(count).toHaveText('0');

  await page.getByTestId('inc').click();
  await expect(count).toHaveText('1');
  await page.getByTestId('inc').click();
  await expect(count).toHaveText('2');
  await page.getByTestId('dec').click();
  await expect(count).toHaveText('1');

  // one-shot components own no server state: the store is gone with the page
  await page.reload();
  await expect(page.getByTestId('count')).toHaveText('0');
});
