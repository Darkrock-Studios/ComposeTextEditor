import { test } from '@playwright/test';
import { expectText, openBlankEditor } from './editor';

// Real key presses in Chromium against the built wasm demo.

test.beforeEach(async ({ page }) => {
	await openBlankEditor(page);
});

test('typed keys insert text', async ({ page }) => {
	await page.keyboard.type('Hello web');
	await expectText(page, 'Hello web');
});

test('Enter splits the line and Backspace deletes', async ({ page }) => {
	await page.keyboard.type('one');
	await page.keyboard.press('Enter');
	await page.keyboard.type('two');
	await page.keyboard.press('Backspace');
	await expectText(page, 'one\ntw');
});

test('typing replaces a Shift+Arrow selection', async ({ page }) => {
	await page.keyboard.type('hello');
	await page.keyboard.press('Shift+ArrowLeft');
	await page.keyboard.press('Shift+ArrowLeft');
	await page.keyboard.type('p');
	await expectText(page, 'help');
});

test('arrow keys move the caret for an insert in the middle', async ({ page }) => {
	await page.keyboard.type('abc');
	await page.keyboard.press('ArrowLeft');
	await page.keyboard.type('X');
	await page.keyboard.press('Home');
	await page.keyboard.type('>');
	await expectText(page, '>abXc');
});

// Semicolon and equals share key codes with named keys on the canvas path.
test('semicolon and equals type themselves', async ({ page }) => {
	await page.keyboard.type('a;b=c');
	await expectText(page, 'a;b=c');
});
