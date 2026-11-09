import { test, expect } from '@playwright/test';
import { installConsoleGuard } from './helpers/console-guard';

installConsoleGuard();

test('todomvc: Enter adds, checkboxes toggle via query params, filters hide', async ({ page }) => {
  await page.goto('/examples/todomvc');
  const input = page.getByTestId('new-todo');
  await expect(input).toBeVisible();

  await input.fill('buy milk');
  await input.press('Enter');
  await expect(page.getByTestId('todo-1')).toContainText('buy milk');
  await expect(input).toHaveValue(''); // the action cleared the bound signal

  await input.fill('walk the dog');
  await input.press('Enter');
  await expect(page.getByTestId('todo-2')).toContainText('walk the dog');
  await expect(page.getByTestId('remaining')).toHaveText('2 items left');

  await page.getByTestId('toggle-1').check();
  await expect(page.getByTestId('remaining')).toHaveText('1 item left');
  await expect(page.getByTestId('todo-1')).toHaveClass(/completed/);

  await page.getByTestId('filter-active').click();
  await expect(page.getByTestId('todo-1')).toHaveCount(0);
  await expect(page.getByTestId('todo-2')).toBeVisible();

  await page.getByTestId('filter-completed').click();
  await expect(page.getByTestId('todo-1')).toBeVisible();
  await expect(page.getByTestId('todo-2')).toHaveCount(0);

  await page.getByTestId('filter-all').click();
  await expect(page.getByTestId('todo-2')).toBeVisible();
  await page.getByTestId('clear-completed').click();
  await expect(page.getByTestId('todo-1')).toHaveCount(0);
  await expect(page.getByTestId('remaining')).toHaveText('1 item left');
});

test('todomvc: double-click edits, Enter saves, Escape cancels', async ({ page }) => {
  await page.goto('/examples/todomvc');
  const input = page.getByTestId('new-todo');
  await input.fill('draft');
  await input.press('Enter');
  await expect(page.getByTestId('todo-1')).toContainText('draft');

  await page.getByTestId('todo-1').locator('.text').dblclick();
  const edit = page.getByTestId('todo-1').locator('input[type=text]');
  await expect(edit).toHaveValue('draft');
  await edit.fill('final');
  await edit.press('Enter');
  await expect(page.getByTestId('todo-1')).toContainText('final');

  await page.getByTestId('todo-1').locator('.text').dblclick();
  await page.getByTestId('todo-1').locator('input[type=text]').fill('discarded');
  await page.getByTestId('todo-1').locator('input[type=text]').press('Escape');
  await expect(page.getByTestId('todo-1')).toContainText('final');
});
