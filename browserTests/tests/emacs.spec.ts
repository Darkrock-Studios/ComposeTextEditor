import { expect, test, type Page } from '@playwright/test';
import { awaitFieldCaret, expectText, inputField, openBlankEditor } from './editor';

/*
 * macOS gives every text view Cocoa's Emacs-style Ctrl chords, the browser's textarea
 * included, and the editor binds the same chords. Compose turns the textarea's own
 * backspace into an edit unless the key was Backspace itself, so Ctrl+H deleted twice in
 * Chrome. The page is told it runs on a Mac, whatever the host.
 */

const mac = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
test.use({ userAgent: mac });

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => Object.defineProperty(Navigator.prototype, 'platform', { get: () => 'MacIntel' }));
	await openBlankEditor(page);
	await page.keyboard.type('abcd');
	await expectText(page, 'abcd');
	await awaitFieldCaret(page, 4);
});

/** Dispatches [key] with Ctrl (or Cmd) on the field, and answers whether its default was prevented. */
async function chord(page: Page, key: string, modifier: 'ctrlKey' | 'metaKey' = 'ctrlKey'): Promise<boolean> {
	const prevented = await inputField(page).evaluate((field, [key, modifier]) => {
		const init = { key, code: `Key${key.toUpperCase()}`, [modifier]: true, bubbles: true, cancelable: true, composed: true };
		const down = new KeyboardEvent('keydown', init);
		field.dispatchEvent(down);
		field.dispatchEvent(new KeyboardEvent('keyup', init));
		return down.defaultPrevented;
	}, [key, modifier] as const);
	// The session handles the field's key events on the next animation frame.
	await page.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))));
	return prevented;
}

// Playwright sends the Cocoa editing command with the key only on a macOS host, so this
// reproduces the double delete there; elsewhere the next test is the guard.
test('Ctrl+H deletes one character', async ({ page }) => {
	await page.keyboard.press('Control+h');
	await expectText(page, 'abc');
	await page.keyboard.type('x');
	await expectText(page, 'abcx');
});

test("the textarea's own action is prevented for Ctrl chords", async ({ page }) => {
	for (const key of ['a', 'e', 'f', 'b', 'n', 'p', 'd', 'h', 'k', 'y', 'o', 't']) {
		expect(await chord(page, key), `Ctrl+${key}`).toBe(true);
	}
});

// Cmd+C, X and V must reach the textarea, whose copy, cut and paste events carry the clipboard.
test('Cmd chords keep their default', async ({ page }) => {
	for (const key of ['a', 'c', 'x', 'v', 'h']) {
		expect(await chord(page, key, 'metaKey'), `Cmd+${key}`).toBe(false);
	}
});

// The Japanese input method converts with Ctrl+J, K and L.
test('a Ctrl chord inside a composition keeps its default', async ({ page }) => {
	const prevented = await inputField(page).evaluate(field => {
		const down = new KeyboardEvent('keydown', { key: 'k', code: 'KeyK', ctrlKey: true, isComposing: true, bubbles: true, cancelable: true, composed: true });
		field.dispatchEvent(down);
		return down.defaultPrevented;
	});
	expect(prevented).toBe(false);
});
