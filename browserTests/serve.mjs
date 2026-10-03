// Serves the built wasm demo for the browser tests: node serve.mjs <dir> <port>
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, isAbsolute, join, relative, resolve } from 'node:path';

const [dir, port] = process.argv.slice(2);
if (!dir || !port) throw new Error('usage: node serve.mjs <dir> <port>');
const root = resolve(dir);

const types = {
	'.html': 'text/html; charset=utf-8',
	'.js': 'text/javascript; charset=utf-8',
	'.mjs': 'text/javascript; charset=utf-8',
	'.wasm': 'application/wasm',
	'.json': 'application/json',
	'.css': 'text/css',
	'.png': 'image/png',
	'.svg': 'image/svg+xml',
	'.ttf': 'font/ttf',
	'.otf': 'font/otf',
	'.txt': 'text/plain; charset=utf-8',
};

const server = createServer(async (request, response) => {
	try {
		const path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
		const file = join(root, path.endsWith('/') ? `${path}index.html` : path);
		const inside = relative(root, file);
		if (inside.startsWith('..') || isAbsolute(inside)) {
			response.writeHead(403).end();
			return;
		}
		const body = await readFile(file);
		response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(body);
	} catch {
		response.writeHead(404).end();
	}
});
server.on('error', error => {
	console.error(`serve.mjs: ${error.message}`);
	process.exit(1);
});
server.listen(Number(port), '127.0.0.1', () => console.log(`serving ${root} on http://127.0.0.1:${port}`));
