import type { CDPSession, Page } from '@playwright/test';

/*
 * Chromium's input method, driven through the DevTools protocol: Input.imeSetComposition
 * and Input.insertText make the browser fire the same compositionstart,
 * compositionupdate, beforeinput (insertCompositionText), input and compositionend
 * events on the focused field that an operating system input method does, and edit the
 * field as one would. Chromium only.
 */
export class Ime {
	private constructor(private readonly cdp: CDPSession) {}

	static async attach(page: Page): Promise<Ime> {
		return new Ime(await page.context().newCDPSession(page));
	}

	/** Starts or updates the composition to [text], the caret at its end. */
	async compose(text: string) {
		await this.cdp.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length });
	}

	/** Commits [text], ending any composition. */
	async commit(text: string) {
		await this.cdp.send('Input.insertText', { text });
	}

	/** Ends the composition with nothing, as Escape does in most input methods. */
	async cancel() {
		await this.compose('');
	}

	/** A key event outside the composition, for the dead key that starts one. */
	async key(type: 'keyDown' | 'keyUp', key: string, code: string, keyCode: number) {
		await this.cdp.send('Input.dispatchKeyEvent', { type, key, code, windowsVirtualKeyCode: keyCode });
	}
}

/**
 * Starts recording the page's composition and `beforeinput` events, as `type:data` or
 * `beforeinput:inputType:data`. They are composed events, so a listener on the document
 * sees them from the input session's field in its shadow root, and keeps seeing them if
 * the session replaces the field.
 */
export async function recordInputEvents(page: Page) {
	await page.evaluate(() => {
		const log: string[] = [];
		(window as any).__inputEvents = log;
		for (const type of ['compositionstart', 'compositionupdate', 'compositionend', 'beforeinput']) {
			document.addEventListener(type, event => {
				const e = event as CompositionEvent & InputEvent;
				log.push(type === 'beforeinput' ? `${type}:${e.inputType}:${e.data ?? ''}` : `${type}:${e.data ?? ''}`);
			}, true);
		}
	});
	return () => page.evaluate(() => (window as any).__inputEvents as string[]);
}
