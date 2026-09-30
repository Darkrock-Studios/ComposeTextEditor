import { expect, test } from '@playwright/test';
import { awaitFieldCaret, expectText, openBlankEditor } from './editor';
import { Ime, recordInputEvents } from './ime';

// Composition in Chromium against the built wasm demo (4.15): a dead key and a
// Japanese composition through the browser's own input method events.

test.beforeEach(async ({ page }) => {
	await openBlankEditor(page);
});

/** Whether [events] hold one composition, ended by [committed], and no plain insert. */
function expectOneComposition(events: string[], committed: string) {
	expect(events.filter(event => event.startsWith('composition') && !event.startsWith('compositionupdate')))
		.toEqual(['compositionstart:', `compositionend:${committed}`]);
	expect(events.filter(event => event.startsWith('beforeinput:insertText'))).toEqual([]);
}

test('a dead key composes one accented letter', async ({ page }) => {
	const events = await recordInputEvents(page);
	const ime = await Ime.attach(page);

	// A dead acute, as a US International layout sends it, then e.
	await ime.key('keyDown', 'Dead', 'Quote', 222);
	await ime.compose('´');
	await ime.key('keyUp', 'Dead', 'Quote', 222);
	await ime.compose('é');
	await ime.commit('é');

	await expectText(page, 'é');
	expectOneComposition(await events(), 'é');
	expect(await events()).toContain('beforeinput:insertCompositionText:´');
});

test('a Japanese composition shows while composing and commits its conversion once', async ({ page }) => {
	const events = await recordInputEvents(page);
	const ime = await Ime.attach(page);

	// Romaji to kana, a backspace inside the composition, then conversion to kanji.
	for (const step of ['n', 'に', 'にh', 'にほ']) await ime.compose(step);
	await expectText(page, 'にほ');
	await ime.compose('に');
	await expectText(page, 'に');
	await ime.compose('にほん');
	await ime.compose('日本');
	await ime.commit('日本');

	await expectText(page, '日本');
	expectOneComposition(await events(), '日本');
});

// Lands at the line end in some runs.
test.fixme('a composition lands at the caret in the middle of a line (4.34)', async ({ page }) => {
	const ime = await Ime.attach(page);
	await page.keyboard.type('ab');
	await page.keyboard.press('ArrowLeft');
	await awaitFieldCaret(page, 1);

	await ime.compose('k');
	await ime.compose('か');
	await ime.commit('か');

	await expectText(page, 'aかb');
});

// Lands at offset 1 in most runs.
test.fixme('a composition after Home lands at the line start (4.34)', async ({ page }) => {
	const ime = await Ime.attach(page);
	await page.keyboard.type('ab');
	await page.keyboard.press('Home');
	await awaitFieldCaret(page, 0);

	await ime.compose('か');
	await ime.commit('か');

	await expectText(page, 'かab');
});

test('a cancelled composition leaves the text as it was', async ({ page }) => {
	const ime = await Ime.attach(page);
	await page.keyboard.type('ab');

	await ime.compose('x');
	await expectText(page, 'abx');
	await ime.cancel();

	await expectText(page, 'ab');
});

test('typing after a commit continues after it', async ({ page }) => {
	const ime = await Ime.attach(page);
	await ime.compose('にほん');
	await ime.commit('日本');
	await page.keyboard.type('!');

	await expectText(page, '日本!');
});
