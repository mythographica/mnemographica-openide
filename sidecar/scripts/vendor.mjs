// Downloads the browser libraries the graph webview loads from a CDN in VS
// Code (mnemographica/src/webview/panel.ts) into dist/vendor/, so the
// OpenIDE host can serve them through mnemo-res:// and the panel works
// offline. The host rewrites the two CDN <script src> URLs to these files.
//
//   d3     https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js            (7.9.0 when written)
//          sha256 f2094bbf6141b359722c4fe454eb6c4b0f0e42cc10cc7af921fc158fceb86539
//   three  https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js (0.160.0, r160)
//          sha256 170c6789f43217c96b3170f4b42fafe135de7f7cd48497a4218f9757ee1d49fa
//
// The build prints the sha256 of every fresh download; `d3@7` floats
// within 7.x, so a different hash after a d3 release is expected.
//
// The URLs are exactly the ones in panel.ts; keep them in sync when
// upstream bumps a version. A file that is already present and non-empty
// is kept, so offline rebuilds work once the files have been fetched.
//
// Usage: node scripts/vendor.mjs   (esbuild.mjs calls vendor() on every build)
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

export const VENDOR_FILES = [
	{ name: 'd3.min.js', url: 'https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js' },
	{ name: 'three.min.js', url: 'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js' }
];

const present = (file) => existsSync(file) && statSync(file).size > 0;

const download = async (vendorDir, { name, url }) => {
	const target = join(vendorDir, name);
	if (present(target)) {
		return `${name} (cached)`;
	}
	let body;
	try {
		const response = await fetch(url);
		if (!response.ok) {
			throw new Error(`HTTP ${response.status}`);
		}
		body = Buffer.from(await response.arrayBuffer());
	} catch (error) {
		throw new Error(`vendor: could not download ${url} into ${target}: ${error.message}. `
			+ 'Connect to the network once, or put the file there by hand.');
	}
	if (body.length === 0) {
		throw new Error(`vendor: ${url} returned an empty body`);
	}
	const partial = `${target}.part`;
	writeFileSync(partial, body);
	renameSync(partial, target);
	const sha256 = createHash('sha256').update(body).digest('hex');
	return `${name} ${(body.length / 1024).toFixed(0)} KB sha256 ${sha256}`;
};

export const vendor = async (dist = join(here, '..', 'dist')) => {
	const vendorDir = join(dist, 'vendor');
	mkdirSync(vendorDir, { recursive: true });
	const results = await Promise.all(VENDOR_FILES.map(file => download(vendorDir, file)));
	console.log(`dist/vendor: ${results.join(', ')}`);
};

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	vendor().catch((error) => {
		console.error(error.message);
		process.exit(1);
	});
}
