import { test, type Page } from '@playwright/test';
import { expectText, inputField, openBlankEditor } from './editor';

/*
 * Shortcuts follow the active keyboard layout (4.38). Chromium cannot switch layouts, so
 * these dispatch key events shaped as another layout sends them: `code` is the key's US
 * QWERTY position, `key` the character the layout types there. The page is told it runs
 * on Linux, whatever the host, so the bindings are Ctrl+Z for undo and Ctrl+Y for redo.
 */

const linux = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
test.use({ userAgent: linux });

async function chord(page: Page, code: string, key: string, modifiers: { ctrlKey?: boolean } = {}) {
	await inputField(page).evaluate((field, init) => {
		const event = { bubbles: true, cancelable: true, composed: true, ...init };
		field.dispatchEvent(new KeyboardEvent('keydown', event));
		field.dispatchEvent(new KeyboardEvent('keyup', event));
	}, { code, key, ...modifiers });
	// The session handles the field's key events on the next animation frame.
	await page.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))));
}

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => Object.defineProperty(Navigator.prototype, 'platform', { get: () => 'Linux x86_64' }));
	await openBlankEditor(page);
	await page.keyboard.type('abc');
	await expectText(page, 'abc');
});

test('Ctrl on the key AZERTY puts z on undoes', async ({ page }) => {
	await chord(page, 'KeyW', 'z', { ctrlKey: true });
	await expectText(page, '');
});

test('Ctrl on QWERTY Z does not undo where the layout types another character there', async ({ page }) => {
	await chord(page, 'KeyZ', 'w', { ctrlKey: true });
	await chord(page, 'KeyZ', 'à', { ctrlKey: true });
	await chord(page, 'KeyZ', ';', { ctrlKey: true });
	await page.keyboard.type('d');
	await expectText(page, 'abcd');
});

test('Ctrl on the key QWERTZ puts y on redoes', async ({ page }) => {
	await chord(page, 'KeyW', 'z', { ctrlKey: true });
	await expectText(page, '');
	await chord(page, 'KeyZ', 'y', { ctrlKey: true });
	await expectText(page, 'abc');
});

test('a Cyrillic layout keeps its shortcuts on the QWERTY letters', async ({ page }) => {
	await chord(page, 'KeyZ', 'я', { ctrlKey: true });
	await expectText(page, '');
});
