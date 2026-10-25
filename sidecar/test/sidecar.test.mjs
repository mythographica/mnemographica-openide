// End-to-end: dist/main.js under a mock host, driven over JSON-RPC
// against the extension's own test fixtures.
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, sep } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { distDir, fixture, startSidecar, walk, workspaceLoaded } from './harness.mjs';

const manifest = JSON.parse(readFileSync(join(distDir, 'package.json'), 'utf8'));
const VIEW_IDS = ['mnemonicaLiveTrace', 'mnemonicaUsages', 'mnemonicaTypes', 'mnemonicaFlow', 'mnemonicaGen', 'mnemonicaDiamonds', 'mnemonicaBagels'];
// Views fixtures-v2 (real tactica output) has data for at load time;
// Live Trace fills from runtime traces, Usages from a Types selection
const VIEWS_WITH_DATA = ['mnemonicaTypes', 'mnemonicaFlow', 'mnemonicaGen', 'mnemonicaDiamonds', 'mnemonicaBagels'];

const isInside = (child, parent) => child === parent || child.startsWith(parent + sep);

const assertHealthy = (sidecar) => {
	assert.deepEqual(sidecar.protocolErrors, [], 'stdout carried only JSON-RPC');
	assert.doesNotMatch(sidecar.stderr, /uncaught exception|unhandled rejection/i);
};

const findNode = async (sidecar, viewId, predicate, depth = 4) => {
	const nodes = await walk(sidecar, viewId, depth);
	return nodes.find(predicate);
};

describe('fixtures-v2', () => {
	const root = fixture('fixtures-v2');
	let sidecar;

	before(async () => {
		sidecar = await startSidecar({ projectRoots: [root] });
		await workspaceLoaded(sidecar);
	});
	after(() => sidecar?.kill());

	it('initialize returns the 7 views, the contributed commands, menus and configuration', () => {
		const result = sidecar.initializeResult;
		assert.deepEqual(result.views.map(view => view.id), VIEW_IDS);
		for (const view of result.views) {
			assert.equal(view.containerId, 'mnemonica');
			assert.equal(view.containerTitle, 'Mnemonica');
			assert.ok(view.name);
		}
		const commands = new Map(result.commands.map(command => [command.command, command]));
		for (const contributed of manifest.contributes.commands) {
			const info = commands.get(contributed.command);
			assert.ok(info, `${contributed.command} present`);
			assert.equal(info.title, contributed.title);
			assert.equal(info.internal, undefined);
		}
		assert.equal(commands.get('mnemographica.refreshGraph').icon, '$(refresh)');
		// registered by activate() without a package.json contribution
		assert.equal(commands.get('mnemographica.treeItemClick')?.internal, true);
		assert.ok(result.menus['view/title'].length > 0);
		assert.ok(result.menus['view/item/context'].length > 0);
		assert.equal(result.configuration['mnemographica.strategyLogPort'].default, 9250);
	});

	it('every view answers its roots (labels printed)', async (t) => {
		for (const viewId of VIEW_IDS) {
			const roots = await sidecar.getChildren(viewId);
			t.diagnostic(`${viewId}: [${roots.map(node => JSON.stringify(node.label)).join(', ')}]`);
			if (VIEWS_WITH_DATA.includes(viewId)) {
				assert.ok(roots.length > 0, `${viewId} has roots`);
			}
			for (const node of roots) {
				assert.match(node.id, new RegExp(`^${viewId}:\\d+$`));
				assert.ok([0, 1, 2].includes(node.collapsibleState));
			}
		}
	});

	it('walks every view to depth 3 with unique, stable ids', async (t) => {
		for (const viewId of VIEW_IDS) {
			const nodes = await walk(sidecar, viewId, 3);
			const ids = nodes.map(node => node.id);
			assert.equal(new Set(ids).size, ids.length, `${viewId} ids unique`);
			t.diagnostic(`${viewId}: ${nodes.length} nodes to depth 3`);
			const again = await walk(sidecar, viewId, 3);
			assert.deepEqual(again.map(node => node.id), ids, `${viewId} ids stable across getChildren`);
		}
		const types = await walk(sidecar, 'mnemonicaTypes', 3);
		assert.ok(types.some(node => node.depth === 3), 'Types tree is at least 3 levels deep');
		const icons = types.filter(node => node.iconId);
		assert.ok(icons.length > 0, 'ThemeIcons become iconId');
	});

	it('mnemographica.showTypeGraph opens a webview whose page loads mnemo-res: resources', async () => {
		sidecar.reset();
		await sidecar.execute('mnemographica.showTypeGraph');
		const created = await sidecar.waitFor('webview/create');
		assert.equal(created.params.viewType, 'mnemonicaGraph');
		assert.equal(created.params.enableScripts, true);
		assert.deepEqual(created.params.localResourceRoots, [join(distDir, 'media')]);
		const html = await sidecar.waitFor('webview/setHtml', params => params.panelId === created.params.panelId);
		const resources = [...html.params.html.matchAll(/(?:src|href)="(mnemo-res:[^"]+)"/g)].map(match => match[1]);
		assert.ok(resources.length >= 2, 'stylesheet and script are mnemo-res: URIs');
		for (const resource of resources) {
			const url = new URL(resource);
			assert.equal(url.host, 'file');
			assert.ok(isInside(decodeURIComponent(url.pathname), join(distDir, 'media')), `${resource} inside localResourceRoots`);
		}
		const script = resources.find(resource => resource.endsWith('/webview.js'));
		const scriptText = readFileSync(decodeURIComponent(new URL(script).pathname), 'utf8');
		assert.match(scriptText, /acquireVsCodeApi\(\)/, 'the page script talks to the host through acquireVsCodeApi');
		assert.ok(created.at <= html.at, 'webview/create precedes webview/setHtml');

		// the page's 'ready' → the panel pushes the graph
		await sidecar.connection.sendNotification('webview/message', { panelId: created.params.panelId, message: { command: 'ready' } });
		const update = await sidecar.waitFor('webview/postMessage', params => params.message?.command === 'updateGraph');
		assert.ok(update.params.message.data.nodes.length > 0);
	});

	it('mnemographica.openStrategyTab serves inline html that calls acquireVsCodeApi', async () => {
		sidecar.reset();
		await sidecar.execute('mnemographica.openStrategyTab');
		const created = await sidecar.waitFor('webview/create', params => params.viewType === 'mnemographicaStrategy');
		const html = await sidecar.waitFor('webview/setHtml', params => params.panelId === created.params.panelId);
		assert.match(html.params.html, /acquireVsCodeApi\(\)/);
		await sidecar.connection.sendNotification('webview/disposed', { panelId: created.params.panelId });
	});

	it('unknown commands are JSON-RPC -32601', async () => {
		await assert.rejects(sidecar.execute('mnemographica.noSuchCommand'), error => error.code === -32601);
	});

	it('<viewId>.focus from the host (status bar item command) becomes view/focus', async () => {
		sidecar.reset();
		await sidecar.execute('mnemonicaTypes.focus');
		const focus = await sidecar.waitFor('view/focus', params => params.viewId === 'mnemonicaTypes');
		assert.equal(focus.params.viewId, 'mnemonicaTypes');
		await assert.rejects(sidecar.execute('noSuchView.focus'), error => error.code === -32601);
	});

	it('shutdown answers null and exits 0', async () => {
		assertHealthy(sidecar);
		const result = await sidecar.shutdown();
		assert.equal(result.response, null);
		assert.equal(result.code, 0);
	});
});

/**
 * The fixtures ship .tactica only — no sources. Navigation needs the
 * files on disk, so this suite runs on a copy of fixtures-v2 with a stub
 * source file at every define() site of definitions.json.
 */
describe('fixtures-v2 copy with source files', () => {
	const project = mkdtempSync(join(tmpdir(), 'mnemo-sidecar-project-'));
	const definitions = JSON.parse(readFileSync(join(fixture('fixtures-v2'), '.tactica', 'definitions.json'), 'utf8')).definitions;
	let sidecar;

	before(async () => {
		cpSync(join(fixture('fixtures-v2'), '.tactica'), join(project, '.tactica'), { recursive: true });
		const files = new Map();
		for (const [fullPath, entry] of Object.entries(definitions)) {
			const match = /^(.+):(\d+):(\d+)$/.exec(entry.location);
			if (!match) {
				continue;
			}
			const lines = files.get(match[1]) ?? [];
			lines[Number(match[2]) - 1] = `export const ${entry.name} = define('${fullPath}', function () {});`;
			files.set(match[1], lines);
		}
		for (const [file, lines] of files) {
			const absolute = join(project, file);
			mkdirSync(dirname(absolute), { recursive: true });
			writeFileSync(absolute, Array.from(lines, line => line ?? '// stub').join('\n') + '\n');
		}
		mkdirSync(join(project, 'src'), { recursive: true });
		writeFileSync(join(project, 'src', 'consumer.ts'), [
			"import { lookup } from 'mnemonica';",
			"const Sentience = lookup('Sentience');",
			"const User = lookup('UserEntity');",
			''
		].join('\n'));
		sidecar = await startSidecar({ projectRoots: [project] });
		await workspaceLoaded(sidecar);
	});
	after(() => sidecar?.kill());

	it('a navigable tree item command opens its define() site', async () => {
		const node = await findNode(sidecar, 'mnemonicaTypes', candidate => candidate.contextValue === 'navigable' && candidate.label === 'Sentience');
		assert.ok(node?.command, 'Sentience is navigable');
		sidecar.reset();
		await sidecar.execute(node.command.command, node.command.arguments);
		const shown = await sidecar.waitFor('window/showTextDocument');
		assert.ok(isInside(shown.params.path, project), `${shown.params.path} inside the fixture copy`);
		assert.ok(shown.params.path.endsWith(join('src', 'ai-types', 'Sentience', 'index.ts')));
		const [line, column] = definitions.Sentience.location.split(':').slice(-2).map(Number);
		assert.equal(shown.params.selection.start.line, line - 1);
		assert.ok(shown.params.selection.start.character >= column - 1);
		// a collapsible row also unfolds through TreeView.reveal
		const reveal = await sidecar.waitFor('tree/reveal', params => params.viewId === 'mnemonicaTypes');
		assert.equal(reveal.params.itemId, node.id);
		assert.equal(reveal.params.expand, true);
		// …and the selection fills the Usages view
		await sidecar.waitFor('tree/invalidate', params => params.viewId === 'mnemonicaUsages');
	});

	it('an item context-menu command receives the tree element (command/execute context)', async () => {
		const node = await findNode(sidecar, 'mnemonicaTypes', candidate => candidate.label === 'UserEntity');
		assert.ok(node, 'UserEntity row');
		sidecar.reset();
		await sidecar.connection.sendRequest('command/execute', {
			command: 'mnemographica.showUsages',
			args: [],
			context: { viewId: 'mnemonicaTypes', itemId: node.id }
		});
		const pick = await sidecar.waitFor('window/showQuickPick');
		assert.ok(pick.params.items.length >= 1, 'usages of UserEntity offered');
		assert.match(pick.params.placeHolder, /UserEntity/);
	});

	it('definition/provide at a lookup() site returns the define() location', async () => {
		const consumer = join(project, 'src', 'consumer.ts');
		const lineText = "const Sentience = lookup('Sentience');";
		const locations = await sidecar.connection.sendRequest('definition/provide', {
			path: consumer,
			position: { line: 1, character: lineText.indexOf("'Sentience'") + 3 }
		});
		assert.ok(locations.length >= 1);
		assert.ok(locations[0].path.endsWith(join('src', 'ai-types', 'Sentience', 'index.ts')));
		assert.equal(locations[0].range.start.line, Number(definitions.Sentience.location.split(':').at(-2)) - 1);
	});

	it('references/provide on a type name returns its usages', async () => {
		const consumer = join(project, 'src', 'consumer.ts');
		const lineText = "const User = lookup('UserEntity');";
		const locations = await sidecar.connection.sendRequest('references/provide', {
			path: consumer,
			position: { line: 2, character: lineText.indexOf('UserEntity') + 2 },
			includeDeclaration: true
		});
		assert.ok(locations.length >= 1);
		for (const location of locations) {
			assert.ok(isInside(location.path, project));
		}
	});

	it('rewriting a .tactica contract file reloads the workspace (file watcher)', async () => {
		sidecar.reset();
		const hierarchy = join(project, '.tactica', 'hierarchy.json');
		const content = readFileSync(hierarchy, 'utf8');
		const refreshed = sidecar.waitFor('window/showMessage', params => params.message === 'Type graph refreshed', 20000);
		// chokidar gets 'ready' asynchronously: rewrite until the reload lands,
		// spaced wider than the extension's 2 s debounce so a rewrite never
		// postpones the reload it is waiting for
		const timer = setInterval(() => writeFileSync(hierarchy, content), 4000);
		setTimeout(() => writeFileSync(hierarchy, content), 500);
		try {
			await refreshed;
		} finally {
			clearInterval(timer);
		}
		await sidecar.waitFor('tree/invalidate', params => params.viewId === 'mnemonicaTypes');
	});

	it('shutdown exits 0', async () => {
		assertHealthy(sidecar);
		const result = await sidecar.shutdown();
		assert.equal(result.code, 0);
	});
});

// Older / partial layouts: hand-written 1.2 payload, collections-only
// manifests — the extension may show little, but nothing may crash
for (const name of ['fixtures', 'fixtures-collections', 'fixtures-collections-only']) {
	describe(name, () => {
		let sidecar;

		before(async () => {
			sidecar = await startSidecar({ projectRoots: [fixture(name)] });
			await workspaceLoaded(sidecar);
		});
		after(() => sidecar?.kill());

		it('every view walks to depth 3 without errors (root labels printed)', async (t) => {
			for (const viewId of VIEW_IDS) {
				const roots = await sidecar.getChildren(viewId);
				t.diagnostic(`${viewId}: [${roots.map(node => JSON.stringify(node.label)).join(', ')}]`);
				await walk(sidecar, viewId, 3);
			}
		});

		it('shutdown exits 0', async () => {
			assertHealthy(sidecar);
			const result = await sidecar.shutdown();
			assert.equal(result.code, 0);
		});
	});
}
