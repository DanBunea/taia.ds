import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';

installConsoleGuard();

async function reachReview(page, name: string) {
  await page.goto('/examples/wizard');
  await expect(page.getByTestId('state')).toHaveText('[:editing :details]');
  await page.locator('#wizard1-name').fill(name);
  await page.getByTestId('next').click();
  await expect(page.getByTestId('state')).toHaveText('[:editing :address]');
  await page.locator('#wizard1-street').fill('1 Loop Road');
  await page.locator('#wizard1-city').fill('Oxford');
  await page.getByTestId('next').click();
  await expect(page.getByTestId('state')).toHaveText('[:editing :review]');
}

test('wizard: a guard keeps you on the step with an error', async ({ page }) => {
  await page.goto('/examples/wizard');
  await page.getByTestId('next').click();
  await expect(page.getByTestId('error')).toHaveText('Name is required');
  await expect(page.getByTestId('state')).toHaveText('[:editing :details]');
});

test('wizard: the invoke fails back to the review step with the message', async ({ page }) => {
  await reachReview(page, 'fail');
  await page.getByTestId('submit').click();
  await expect(page.getByTestId('error')).toHaveText("The server refused to save (you typed 'fail').");
  await expect(page.getByTestId('state')).toHaveText('[:editing :review]');
});

test('wizard: on-done lands in :done with a receipt; RESET works from any state', async ({ page }) => {
  await reachReview(page, 'Ada');
  await page.getByTestId('submit').click();
  await expect(page.getByTestId('receipt')).toHaveText(/^Saved\. Receipt W-\d{4}$/);
  await expect(page.getByTestId('state')).toHaveText(':done');

  await page.getByRole('button', { name: 'Start over' }).click();
  await expect(page.getByTestId('state')).toHaveText('[:editing :details]');
  await expect(page.locator('#wizard1-name')).toHaveValue('');
});
