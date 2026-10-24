#!/usr/bin/env node
// Fake Mnemonica sidecar: speaks docs/PROTOCOL.md over stdio with canned data, no npm dependencies.
// Run the host against it: ./gradlew runIde -PsidecarMain=$PWD/src/test/resources/fake-sidecar.js
'use strict';

const fs = require('fs');
const path = require('path');

const packageJsonPath = process.env.MNEMO_PACKAGE_JSON
	|| path.resolve(__dirname, '../../../mnemographica/package.json');
const contributes = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8')).contributes;
const log = (...args) => process.stderr.write(`[fake-sidecar] ${args.join(' ')}\n`);

// ---------- Content-Length framing ----------

let buffer = Buffer.alloc(0);
process.stdin.on('data', (chunk) => {
	buffer = Buffer.concat([buffer, chunk]);
	for (;;) {
		const headerEnd = buffer.indexOf('\r\n\r\n');
		if (headerEnd < 0) { return; }
		const match = /Content-Length: *(\d+)/i.exec(buffer.subarray(0, headerEnd).toString('ascii'));
		if (!match) { buffer = buffer.subarray(headerEnd + 4); continue; }
		const length = Number(match[1]);
		if (buffer.length < headerEnd + 4 + length) { return; }
		const body = buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString('utf8');
		buffer = buffer.subarray(headerEnd + 4 + length);
		handle(JSON.parse(body));
	}
});
process.stdin.on('end', () => process.exit(0));

function send(message) {
	const body = Buffer.from(JSON.stringify({ jsonrpc: '2.0', ...message }), 'utf8');
	process.stdout.write(`Content-Length: ${body.length}\r\n\r\n`);
	process.stdout.write(body);
}
const notify = (method, params) => send({ method, params });
let nextId = 1;
const request = (method, params) => send({ id: `fake-${nextId++}`, method, params });

// ---------- canned trees ----------

const views = Object.entries(contributes.views).flatMap(([containerId, list]) => {
	const container = contributes.viewsContainers.activitybar.find(c => c.id === containerId);
	return list.map(v => ({ id: v.id, name: v.name, containerId, containerTitle: container ? container.title : containerId }));
});

// view id -> { contextValue, icon, roots: { label: children | null } }
const canned = {
	mnemonicaTypes     : { contextValue: 'navigable', icon: 'symbol-class', expanded: ['Registry'], roots: { Registry: { Trie: { GraphNodeTrie: null }, Usages: null }, Main: { Adapter: null } } },
	mnemonicaUsages    : { contextValue: 'usageItem', icon: 'references', roots: { 'src/extension.ts': { 'line 42': null, 'line 97': null } } },
	mnemonicaFlow      : { contextValue: 'navigable', icon: 'debug-alt', roots: { 'activate()': { 'loadWorkspace()': { 'refresh()': null } } } },
	mnemonicaGen       : { contextValue: 'genItem', icon: 'symbol-misc', roots: { 'Generation 0': { Registry: null, Main: null }, 'Generation 1': { Trie: null } } },
	mnemonicaDiamonds  : { contextValue: 'diamondSegment', icon: 'symbol-method', roots: { 'creation scope A': { navigable: null } } },
	mnemonicaBagels    : { contextValue: 'bagelSegment', icon: 'circle-outline', roots: { 'wrap site B': { navigable: null } } },
	mnemonicaLiveTrace : { contextValue: 'mnemTraceJaeger', icon: 'pulse', roots: { 'trace #1 (3 edges)': { 'edge 1': null, 'edge 2': null }, 'trace #2 (1 edge)': null } }
};

// id = "<viewId>:<label>/<label>/..."
function subtree(viewId, itemId) {
	let node = canned[viewId] ? canned[viewId].roots : {};
	if (itemId === null || itemId === undefined) { return { node, prefix: `${viewId}:` }; }
	for (const label of itemId.substring(viewId.length + 1).split('/')) {
		node = node && node[label];
	}
	return { node, prefix: `${itemId}/` };
}

function getChildren({ viewId, itemId }) {
	if (itemId !== null && itemId !== undefined) { notify('log', { level: 'info', message: `fake tree/getChildren ${itemId}` }); }
	const spec = canned[viewId];
	const { node, prefix } = subtree(viewId, itemId);
	if (!spec || !node) { return []; }
	return Object.entries(node).map(([label, children]) => ({
		id               : prefix + label,
		label,
		description      : children ? `${Object.keys(children).length} children` : undefined,
		tooltip          : `${viewId} › ${label}`,
		iconId           : spec.icon,
		iconColor        : label.includes('#2') ? 'errorForeground' : undefined,
		collapsibleState : children ? ((spec.expanded || []).includes(label) ? 2 : 1) : 0,
		contextValue     : spec.contextValue,
		command          : { command: 'mnemographica.navigateToLocation', arguments: [{ filePath: __filename, line: 1, column: 1, label }] }
	}));
}

function getParent({ itemId }) {
	const cut = itemId.lastIndexOf('/');
	return cut < 0 ? null : itemId.substring(0, cut);
}

// ---------- dispatch ----------

const handlers = {
	initialize (params) {
		log('initialize', JSON.stringify(params.projectRoots), params.ideVersion);
		for (const view of views) { notify('tree/registered', { viewId: view.id }); }
		notify('statusBar/update', { id: 'psi', text: 'Ψ', tooltip: 'Show Mnemonica Types', command: 'mnemonicaTypes.focus', visible: true, alignment: 'right', priority: 100 });
		notify('output/append', { channel: 'Mnemonica Logger', text: 'fake sidecar activated\n' });
		notify('log', { level: 'info', message: `fake sidecar initialize: ${views.length} views` });
		return {
			commands      : contributes.commands.map(c => ({ command: c.command, title: c.title, category: c.category, icon: c.icon })),
			views,
			menus         : contributes.menus,
			configuration : contributes.configuration
		};
	},
	initialized () {
		notify('tree/setMeta', { viewId: 'mnemonicaLiveTrace', badge: { value: 2, tooltip: '2 traces' }, message: 'Canned data from fake-sidecar.js' });
		notify('view/focus', { viewId: 'mnemonicaTypes' });
		setTimeout(() => notify('tree/reveal', { viewId: 'mnemonicaTypes', itemId: 'mnemonicaTypes:Registry/Trie/GraphNodeTrie', select: true, focus: false, expand: false }), 1500);
	},
	shutdown () {
		setTimeout(() => process.exit(0), 50);
		return null;
	},
	'tree/getChildren' : getChildren,
	'tree/getParent'   : getParent,
	'command/execute' (params) {
		const context = params.context ? ` on ${params.context.itemId}` : '';
		request('window/showMessage', { type: 'info', message: `fake sidecar executed ${params.command}${context} ${JSON.stringify(params.args)}`, items: [] });
		return null;
	},
	'definition/provide' (params) {
		return [{ path: params.path, range: { start: { line: 0, character: 0 }, end: { line: 0, character: 5 } } }];
	},
	'references/provide' (params) {
		return [
			{ path: params.path, range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } } },
			{ path: params.path, range: { start: { line: 1, character: 0 }, end: { line: 1, character: 1 } } }
		];
	},
	'tree/selectionChanged' (params) {
		notify('log', { level: 'info', message: `fake tree/selectionChanged ${params.viewId} ${JSON.stringify(params.itemIds)}` });
	},
	'config/changed' (params) {
		log('config/changed', Object.keys(params.config).length, 'keys');
	}
};

function handle(message) {
	if (message.method === undefined) {
		log('response', JSON.stringify(message));
		return;
	}
	const handler = handlers[message.method];
	const isRequest = message.id !== undefined;
	if (!handler) {
		if (isRequest) { send({ id: message.id, error: { code: -32601, message: `Unhandled method ${message.method}` } }); }
		else { log('notification', message.method, JSON.stringify(message.params)); }
		return;
	}
	try {
		const result = handler(message.params || {});
		if (isRequest) { send({ id: message.id, result: result === undefined ? null : result }); }
	} catch (err) {
		if (isRequest) { send({ id: message.id, error: { code: -32603, message: String(err), data: err.stack } }); }
	}
}

log(`started (node ${process.version}), contributes from ${packageJsonPath}`);
