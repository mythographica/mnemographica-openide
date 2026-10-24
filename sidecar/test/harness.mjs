// Mock IDE host: spawns dist/main.js and speaks the PROTOCOL.md contract
// over its stdio with vscode-jsonrpc, the way the Kotlin host does with
// lsp4j. Every sidecar→host message is recorded; requests get the
// answers a user who dismisses every prompt would give.
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import jsonrpc from 'vscode-jsonrpc/node';

const { createMessageConnection, ResponseError, StreamMessageReader, StreamMessageWriter } = jsonrpc;

const here = dirname(fileURLToPath(import.meta.url));
export const sidecarDir = resolve(here, '..');
export const distDir = join(sidecarDir, 'dist');
export const upstreamDir = resolve(sidecarDir, '..', 'mnemographica');
export const fixture = (name) => join(upstreamDir, 'test', name);

// commands/executeBuiltin commands the host implements (PROTOCOL.md)
const BUILTINS = new Set(['vscode.open', 'revealInExplorer', 'workbench.action.openSettings', 'workbench.view.explorer', 'setContext']);

const defaultConfig = () => {
	const manifest = JSON.parse(readFileSync(join(distDir, 'package.json'), 'utf8'));
	const config = {};
	for (const [key, property] of Object.entries(manifest.contributes.configuration.properties)) {
		config[key] = property.default;
	}
	return config;
};

let portBase = 20000 + Math.floor(Math.random() * 20000);

export async function startSidecar ({ projectRoots, config } = {}) {
	const storagePath = mkdtempSync(join(tmpdir(), 'mnemo-sidecar-storage-'));
	const child = spawn(process.execPath, [join(distDir, 'main.js')], {
		cwd: distDir,
		stdio: ['pipe', 'pipe', 'pipe'],
		env: {
			...process.env,
			// the extension's strategy server: unique ports per test session
			MNEMOGRAPHICA_STRATEGY_PORT: String(portBase++),
			MNEMOGRAPHICA_WS_PORT: String(portBase++)
		}
	});
	let stderr = '';
	child.stderr.setEncoding('utf8');
	child.stderr.on('data', (chunk) => { stderr += chunk; });
	const exited = new Promise(resolveExit => child.on('exit', (code, signal) => resolveExit({ code, signal })));

	const connection = createMessageConnection(new StreamMessageReader(child.stdout), new StreamMessageWriter(child.stdin));
	const events = [];
	const bus = new EventEmitter();
	const protocolErrors = [];
	const record = (kind, method, params) => {
		const event = { kind, method, params, at: Date.now() };
		events.push(event);
		bus.emit('event', event);
	};
	connection.onError(([error]) => protocolErrors.push(String(error)));
	connection.onNotification((method, params) => record('notification', method, params));
	connection.onRequest((method, params) => {
		record('request', method, params);
		if (method === 'commands/executeBuiltin' && !BUILTINS.has(params.command)) {
			throw new ResponseError(-32601, `unsupported built-in command ${params.command}`);
		}
		return null;
	});
	connection.listen();

	/** Resolves with the first recorded event (past or future) matching method + predicate */
	const waitFor = (method, predicate = () => true, timeoutMs = 10000) => new Promise((resolveWait, rejectWait) => {
		const match = (event) => event.method === method && predicate(event.params);
		const already = events.find(match);
		if (already) {
			resolveWait(already);
			return;
		}
		const onEvent = (event) => {
			if (match(event)) {
				clearTimeout(timer);
				bus.off('event', onEvent);
				resolveWait(event);
			}
		};
		const timer = setTimeout(() => {
			bus.off('event', onEvent);
			rejectWait(new Error(`timed out waiting for ${method}; seen: ${[...new Set(events.map(e => e.method))].join(', ')}\n--- sidecar stderr ---\n${stderr.slice(-4000)}`));
		}, timeoutMs);
		bus.on('event', onEvent);
	});

	const initializeResult = await connection.sendRequest('initialize', {
		projectRoots,
		extensionPath: distDir,
		config: config ?? defaultConfig(),
		ideVersion: 'OpenIDE Pro 2026.2.3 (mock host)',
		pluginVersion: '0.0.0-test',
		storagePath,
		theme: 'dark'
	});
	await connection.sendNotification('initialized', {});

	const sidecar = {
		child,
		connection,
		events,
		initializeResult,
		protocolErrors,
		waitFor,
		get stderr () { return stderr; },
		getChildren: (viewId, itemId = null) => connection.sendRequest('tree/getChildren', { viewId, itemId }),
		execute: (command, args = []) => connection.sendRequest('command/execute', { command, args }),
		/** Clears the recorded events, so waitFor sees only what follows */
		reset: () => { events.length = 0; },
		async shutdown () {
			const response = await connection.sendRequest('shutdown');
			const exit = await exited;
			connection.dispose();
			return { response, ...exit };
		},
		kill () {
			if (child.exitCode === null) {
				child.kill();
			}
			connection.dispose();
		}
	};
	return sidecar;
}

/** Breadth-first walk of a view down to `depth` levels; returns every node seen */
export async function walk (sidecar, viewId, depth) {
	const seen = [];
	let level = await sidecar.getChildren(viewId, null);
	for (let d = 1; d <= depth && level.length > 0; d++) {
		seen.push(...level.map(node => ({ ...node, depth: d })));
		if (d === depth) {
			break;
		}
		const next = [];
		for (const node of level) {
			if (node.collapsibleState !== 0) {
				next.push(...await sidecar.getChildren(viewId, node.id));
			}
		}
		level = next;
	}
	return seen;
}

/** Wait until the extension finished loading the workspace into the Types tree */
export const workspaceLoaded = (sidecar) => sidecar.waitFor('tree/invalidate', params => params.viewId === 'mnemonicaTypes', 15000);
