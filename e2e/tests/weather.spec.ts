import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';

installConsoleGuard();

// The server runs with TAIA_EXAMPLES_FAKE_WEATHER=1 (see scripts/e2e.clj):
// lookups answer from a table after ~700ms, so the loading states are visible.
test('weather: INIT paints a skeleton, the lazy LOAD fetches, searches show the in-flight indicator', async ({ page }) => {
  await page.goto('/ds/nds/home.home/home/init?example=weather');

  // 1. the skeleton is the first paint; the fetch happens on the LOAD it fires
  await expect(page.getByTestId('loading')).toBeVisible();
  await expect(page.getByTestId('state')).toHaveText(':loading');
  await expect(page.getByTestId('place')).toHaveText('Barcelona, Spain', { timeout: 10_000 });
  await expect(page.getByTestId('temperature')).toHaveText('19.1 °C');
  await expect(page.getByTestId('state')).toHaveText(':loaded');

  // 2. a quick pick: ?city= captured into :memory, then the invoke
  await page.getByTestId('pick-Oslo').click();
  await expect(page.getByTestId('busy')).toBeVisible(); // data-indicator while the request is in flight
  await expect(page.getByTestId('search')).toBeDisabled();
  await expect(page.getByTestId('place')).toHaveText('Oslo, Norway', { timeout: 10_000 });
  await expect(page.getByTestId('busy')).toBeHidden();
  await expect(page.getByTestId('city')).toHaveValue('Oslo'); // the bound input follows the store

  // 3. the bound input + Enter
  await page.getByTestId('city').fill('Tokyo');
  await page.getByTestId('city').press('Enter');
  await expect(page.getByTestId('place')).toHaveText('Tokyo, Japan', { timeout: 10_000 });
});

test('weather: a refused lookup lands in :failed and a new search recovers', async ({ page }) => {
  await page.goto('/ds/nds/home.home/home/init?example=weather');
  await expect(page.getByTestId('temperature')).toBeVisible({ timeout: 10_000 });

  await page.getByTestId('city').fill('Nowhere-in-particular');
  await page.getByTestId('search').click();
  await expect(page.getByTestId('error')).toHaveText("No place called 'Nowhere-in-particular' was found.", { timeout: 10_000 });
  await expect(page.getByTestId('state')).toHaveText(':failed');

  await page.getByTestId('pick-Lima').click();
  await expect(page.getByTestId('place')).toHaveText('Lima, Peru', { timeout: 10_000 });
  await expect(page.getByTestId('state')).toHaveText(':loaded');
});
