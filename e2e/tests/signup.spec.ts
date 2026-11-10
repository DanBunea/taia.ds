import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';

installConsoleGuard();

test('signup: an empty submit shows both errors and focuses the first invalid field', async ({ page }) => {
  await page.goto('/ds/nds/home.home/home/init?example=signup');
  await page.getByTestId('submit').click();

  await expect(page.getByTestId('error-name')).toHaveText('Tell us your name');
  await expect(page.getByTestId('error-email')).toHaveText('That does not look like an email');
  // the :execute-script effect in the response
  await expect(page.locator('#signup1-name')).toBeFocused();
});

test('signup: data-bind fields reach the server on submit; a valid form lands in :submitted', async ({ page }) => {
  await page.goto('/ds/nds/home.home/home/init?example=signup');
  await page.locator('#signup1-name').fill('Ada');
  await page.locator('#signup1-email').fill('ada@example.com');
  await page.getByTestId('submit').click();

  await expect(page.getByTestId('welcome')).toHaveText('Welcome, Ada!');
  await expect(page.getByText('We would email ada@example.com')).toBeVisible();

  await page.getByRole('button', { name: 'Start over' }).click();
  await expect(page.getByTestId('submit')).toBeVisible();
  await expect(page.locator('#signup1-name')).toHaveValue('');
});
