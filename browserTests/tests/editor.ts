import { expect, type Locator, type Page } from '@playwright/test';

/*
 * The demo draws everything on one canvas. Compose also mirrors the semantics tree into
 * the DOM for accessibility (under the viewport's shadow root), which gives each control
 * its bounds, and gives the editor a contenteditable textbox holding its text. The canvas
 * sits above that tree and takes the pointer, so clicks go to the canvas at a control's
 * bounds. While the editor has focus, Compose's web input session keeps a textarea
 * focused; keys and input method events land there. The browser edits that textarea
 * itself as well, so its value is not proof the editor took an edit: the tests read the
 * editor's text from its semantics node instead.
 */

/** Clicks the canvas at [offset] inside [target]'s bounds, or at its centre. */
export async function clickThroughCanvas(page: Page, target: Locator, offset?: { x: number; y: number }) {
	await target.waitFor();
	const box = await target.boundingBox();
	if (!box) throw new Error(`${target} has no bounds`);
	await page.mouse.click(box.x + (offset?.x ?? box.width / 2), box.y + (offset?.y ?? box.height / 2));
}

/** The textarea Compose's input session focuses while the editor has focus. */
export const inputField = (page: Page) => page.locator('textarea');

/** The editor's node in the accessibility tree. */
export const editorNode = (page: Page) => page.getByRole('textbox', { name: 'Document' });

/** Opens the demo's blank editor and focuses it with a click near its top left. */
export async function openBlankEditor(page: Page) {
	await page.goto('/');
	await clickThroughCanvas(page, page.getByRole('button', { name: 'Markdown Editor (Blank)' }));
	await clickThroughCanvas(page, editorNode(page), { x: 20, y: 20 });
	await expect(inputField(page)).toBeFocused();
}

/** The editor's text as its semantics report it: text nodes, with a line break for each `<br>`. */
export function documentText(page: Page): Promise<string> {
	return editorNode(page).evaluate(node =>
		[...node.childNodes]
			.map(child => (child.nodeName === 'BR' ? '\n' : child.nodeType === Node.TEXT_NODE ? child.textContent : ''))
			.join(''),
	);
}

/** Waits for the editor's text to be [text]. */
export async function expectText(page: Page, text: string) {
	await expect.poll(() => documentText(page)).toBe(text);
}

/**
 * Waits for the input field's caret to reach [offset], with nothing selected. The
 * session copies the editor's caret into the field a frame after a key moves it, and a
 * browser input method composes at the field's caret, so a composition started sooner
 * lands where the caret was. A person cannot start one that fast.
 */
export async function awaitFieldCaret(page: Page, offset: number) {
	await expect
		.poll(() => inputField(page).evaluate(field => {
			const textArea = field as HTMLTextAreaElement;
			return [textArea.selectionStart, textArea.selectionEnd];
		}))
		.toEqual([offset, offset]);
}
