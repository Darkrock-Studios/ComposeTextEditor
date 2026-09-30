import { defineConfig, devices } from '@playwright/test';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

// Relative to this directory, wherever Playwright is started from.
const demo = resolve(__dirname, process.env.DEMO_DIR ?? '../sampleApp/build/dist/wasmJs/productionExecutable');
const port = Number(process.env.DEMO_PORT ?? 8765);

if (!existsSync(join(demo, 'index.html'))) {
	throw new Error(`No demo at ${demo}: run ./gradlew :sampleApp:wasmJsBrowserDistribution first, or set DEMO_DIR`);
}

export default defineConfig({
	testDir: './tests',
	timeout: 60_000,
	expect: { timeout: 10_000 },
	// One page at a time, as a person types: with several at once the input session
	// can end a composition early (4.35), which the fixme cases record.
	workers: 1,
	forbidOnly: !!process.env.CI,
	retries: 0,
	reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
	use: {
		baseURL: `http://127.0.0.1:${port}`,
		trace: 'retain-on-failure',
	},
	webServer: {
		command: `node serve.mjs ${JSON.stringify(demo)} ${port}`,
		url: `http://127.0.0.1:${port}/index.html`,
		reuseExistingServer: !process.env.CI,
	},
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1000, height: 700 } } }],
});
