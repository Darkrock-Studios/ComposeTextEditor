import { expect, test, type Page } from '@playwright/test';
import { clickThroughCanvas, inputField, openBlankEditor } from './editor';

/*
 * A phone keyboard reads the field's attributes as it rises, on the field's first focus.
 * Compose creates its backing field with `autocapitalize="off"` and focuses it at
 * once, so the attribute a field holds as its first `focus()` returns is the one a
 * keyboard sees.
 */

interface FirstFocus { tag: string; autocapitalize: string | null }

/** Records each backing field's `autocapitalize` as its first `focus()` returns. */
async function recordFirstFocus(page: Page) {
	await page.addInitScript(() => {
		const seen: { tag: string; autocapitalize: string | null }[] = [];
		const focused = new WeakSet<Element>();
		(window as unknown as { firstFocus: typeof seen }).firstFocus = seen;
		const focus = HTMLElement.prototype.focus;
		HTMLElement.prototype.focus = function (this: HTMLElement, options?: FocusOptions) {
			focus.call(this, options);
			if (!this.matches('.compose-backing-field') || focused.has(this)) return;
			focused.add(this);
			seen.push({ tag: this.tagName, autocapitalize: this.getAttribute('autocapitalize') });
		};
	});
}

const firstFocus = (page: Page) =>
	page.evaluate(() => (window as unknown as { firstFocus: FirstFocus[] }).firstFocus);

test('the editor field asks for sentence capitals by its first focus', async ({ page }) => {
	await recordFirstFocus(page);
	await openBlankEditor(page);
	expect(await firstFocus(page)).toEqual([{ tag: 'TEXTAREA', autocapitalize: 'sentences' }]);
});

test('a text field focused from the editor keeps its own capitalisation', async ({ page }) => {
	await recordFirstFocus(page);
	await page.goto('/');
	await clickThroughCanvas(page, page.getByRole('button', { name: 'Find Demo (Ctrl+F)' }));
	// The find bar is closed, so the editor is the page's one text box.
	await clickThroughCanvas(page, page.getByRole('textbox'), { x: 20, y: 20 });
	await expect(inputField(page)).toBeFocused();
	await page.keyboard.press('ControlOrMeta+f');
	await expect(page.locator('input.compose-backing-field')).toBeFocused();
	expect(await firstFocus(page)).toEqual([
		{ tag: 'TEXTAREA', autocapitalize: 'sentences' },
		{ tag: 'INPUT', autocapitalize: 'off' },
	]);
});
