/**
 * `vscode.workspace`: folders = the IDE project's content roots,
 * configuration = the host's settings snapshot, files = the local disk
 * (the sidecar runs on the same machine as the IDE).
 */
import * as chokidar from 'chokidar';
import * as fs from 'fs';
import * as path from 'path';
import type * as api from 'vscode-api';
import { log, rpc, warnUnsupported } from '../host';
import { FsWatchersNotification } from '../protocol';
import { SnapshotTextDocument } from './document';
import { globToRegExp } from './glob';
import { Disposable, EventEmitter, Uri } from './types';

// --------------------------------------------------------------- folders

let folders: api.WorkspaceFolder[] = [];
const onDidChangeWorkspaceFoldersEmitter = new EventEmitter<api.WorkspaceFoldersChangeEvent>();

export const setWorkspaceFolders = (roots: string[]): void => {
	const next = roots.map((root, index) => ({ uri: Uri.file(root), name: path.basename(root), index }));
	const before = new Set(folders.map(folder => folder.uri.fsPath));
	const after = new Set(roots);
	const added = next.filter(folder => !before.has(folder.uri.fsPath));
	const removed = folders.filter(folder => !after.has(folder.uri.fsPath));
	const initial = folders.length === 0 && roots.length > 0 && removed.length === 0;
	folders = next;
	if (!initial && (added.length > 0 || removed.length > 0)) {
		onDidChangeWorkspaceFoldersEmitter.fire({ added, removed });
	}
};

const isInside = (child: string, parent: string): boolean => {
	const relative = path.relative(parent, child);
	return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
};

// --------------------------------------------------------- configuration

let configValues = new Map<string, unknown>();
let configDefaults = new Map<string, unknown>();
const onDidChangeConfigurationEmitter = new EventEmitter<api.ConfigurationChangeEvent>();

/** contributes.configuration defaults: the fallback for keys the host did not send */
export const setConfigurationDefaults = (defaults: Record<string, unknown>): void => {
	configDefaults = new Map(Object.entries(defaults));
};

const changeEvent = (changedKeys: string[]): api.ConfigurationChangeEvent => ({
	affectsConfiguration: (section: string) => changedKeys.some(key => key === section || key.startsWith(`${section}.`) || section.startsWith(`${key}.`))
});

/** Full snapshot from the host (initialize, config/changed) */
export const setConfiguration = (snapshot: Record<string, unknown>, notify: boolean): void => {
	const next = new Map(Object.entries(snapshot));
	const keys = new Set([...configValues.keys(), ...next.keys()]);
	const changed = [...keys].filter(key => JSON.stringify(configValues.get(key)) !== JSON.stringify(next.get(key)));
	configValues = next;
	if (notify && changed.length > 0) {
		onDidChangeConfigurationEmitter.fire(changeEvent(changed));
	}
};

const lookupConfig = (fullKey: string): unknown => {
	if (configValues.has(fullKey)) {
		return configValues.get(fullKey);
	}
	if (configDefaults.has(fullKey)) {
		return configDefaults.get(fullKey);
	}
	// A section prefix ("mnemographica") reads as an object of its keys
	const prefix = `${fullKey}.`;
	const nested: Record<string, unknown> = {};
	for (const source of [configDefaults, configValues]) {
		for (const [key, value] of source) {
			if (key.startsWith(prefix)) {
				nested[key.slice(prefix.length)] = value;
			}
		}
	}
	return Object.keys(nested).length > 0 ? nested : undefined;
};

const getConfiguration = (section?: string, _scope?: api.ConfigurationScope | null): api.WorkspaceConfiguration => {
	const full = (key: string) => (section ? `${section}.${key}` : key);
	const configuration: api.WorkspaceConfiguration = {
		get<T> (key: string, defaultValue?: T): T | undefined {
			const value = lookupConfig(full(key));
			return value === undefined ? defaultValue : value as T;
		},
		has (key: string): boolean {
			return lookupConfig(full(key)) !== undefined;
		},
		inspect<T> (key: string) {
			const fullKey = full(key);
			const inspected = {
				key: fullKey,
				defaultValue: configDefaults.get(fullKey) as T | undefined,
				workspaceValue: configValues.get(fullKey) as T | undefined
			};
			return inspected;
		},
		update (key: string, value: unknown): Thenable<void> {
			// Settings are written by the host's settings page; v1 has no
			// sidecar→host config/update — the change stays in this session
			const fullKey = full(key);
			log.warn(`configuration update of '${fullKey}' is kept for this session only (no host write-back in v1)`);
			configValues.set(fullKey, value);
			onDidChangeConfigurationEmitter.fire(changeEvent([fullKey]));
			return Promise.resolve();
		}
	};
	return configuration;
};

// ----------------------------------------------------------------- globs

type patternSpec = { bases: string[]; matcher: RegExp; glob: string };

const toPatternSpec = (pattern: api.GlobPattern): patternSpec => {
	if (typeof pattern === 'string') {
		return { bases: folders.map(folder => folder.uri.fsPath), matcher: globToRegExp(pattern), glob: pattern };
	}
	const base = pattern.baseUri ? pattern.baseUri.fsPath : pattern.base;
	return { bases: [base], matcher: globToRegExp(pattern.pattern), glob: `${base}/${pattern.pattern}` };
};

const toPosix = (value: string): string => value.split(path.sep).join('/');

// ------------------------------------------------------- file watchers

const WATCH_IGNORED = new Set(['node_modules', '.git']);

type rootWatcher = { watcher: chokidar.FSWatcher; listeners: Set<(event: 'add' | 'change' | 'unlink', file: string) => void> };

const rootWatchers = new Map<string, rootWatcher>();
const activeGlobs = new Map<object, string>();

const announceWatchers = (): void => {
	rpc().sendNotification(FsWatchersNotification, { globs: [...activeGlobs.values()] }).catch(() => undefined);
};

// One chokidar instance per root, shared by every watcher on it
const subscribeRoot = (root: string, listener: (event: 'add' | 'change' | 'unlink', file: string) => void): Disposable => {
	let entry = rootWatchers.get(root);
	if (!entry) {
		const watcher = chokidar.watch(root, {
			ignoreInitial: true,
			ignored: (file: string) => WATCH_IGNORED.has(path.basename(file))
		});
		const created: rootWatcher = { watcher, listeners: new Set() };
		for (const event of ['add', 'change', 'unlink'] as const) {
			watcher.on(event, (file: string) => {
				for (const notify of created.listeners) {
					notify(event, file);
				}
			});
		}
		watcher.on('error', (error: unknown) => log.warn(`file watcher on ${root}: ${String(error)}`));
		rootWatchers.set(root, created);
		entry = created;
	}
	const current = entry;
	current.listeners.add(listener);
	return new Disposable(() => {
		current.listeners.delete(listener);
		if (current.listeners.size === 0) {
			rootWatchers.delete(root);
			current.watcher.close().catch(() => undefined);
		}
	});
};

const createFileSystemWatcher = (globPattern: api.GlobPattern, ignoreCreateEvents = false, ignoreChangeEvents = false, ignoreDeleteEvents = false): api.FileSystemWatcher => {
	const spec = toPatternSpec(globPattern);
	const onDidCreate = new EventEmitter<api.Uri>();
	const onDidChange = new EventEmitter<api.Uri>();
	const onDidDelete = new EventEmitter<api.Uri>();
	const emitters = { add: ignoreCreateEvents ? undefined : onDidCreate, change: ignoreChangeEvents ? undefined : onDidChange, unlink: ignoreDeleteEvents ? undefined : onDidDelete };
	const subscriptions = spec.bases.map(base => subscribeRoot(base, (event, file) => {
		const emitter = emitters[event];
		if (emitter && spec.matcher.test(toPosix(path.relative(base, file)))) {
			emitter.fire(Uri.file(file));
		}
	}));
	const handle = {};
	activeGlobs.set(handle, spec.glob);
	announceWatchers();
	const watcher: api.FileSystemWatcher = {
		ignoreCreateEvents,
		ignoreChangeEvents,
		ignoreDeleteEvents,
		onDidCreate: onDidCreate.event,
		onDidChange: onDidChange.event,
		onDidDelete: onDidDelete.event,
		dispose: () => {
			subscriptions.forEach(subscription => subscription.dispose());
			onDidCreate.dispose();
			onDidChange.dispose();
			onDidDelete.dispose();
			if (activeGlobs.delete(handle)) {
				announceWatchers();
			}
		}
	};
	return watcher;
};

/** Close every chokidar instance (shutdown) */
export const closeWatchers = async (): Promise<void> => {
	const closing = [...rootWatchers.values()].map(entry => entry.watcher.close());
	rootWatchers.clear();
	await Promise.allSettled(closing);
};

// ------------------------------------------------------------- findFiles

// VS Code's default files.exclude, applied when `exclude` is undefined
const DEFAULT_EXCLUDE = '**/{.git,.svn,.hg,CVS,.DS_Store,Thumbs.db}';

const findFiles = async (include: api.GlobPattern, exclude?: api.GlobPattern | null, maxResults?: number, token?: api.CancellationToken): Promise<Uri[]> => {
	const spec = toPatternSpec(include);
	const excludeMatcher = exclude === null ? undefined : globToRegExp(typeof exclude === 'string' ? exclude : exclude ? exclude.pattern : DEFAULT_EXCLUDE);
	const limit = maxResults ?? Number.POSITIVE_INFINITY;
	const found: Uri[] = [];
	const visited = new Set<string>();
	const walk = async (base: string, dir: string): Promise<void> => {
		if (found.length >= limit || token?.isCancellationRequested) {
			return;
		}
		let real: string;
		try {
			real = await fs.promises.realpath(dir);
		} catch {
			return;
		}
		if (visited.has(real)) {
			return;
		}
		visited.add(real);
		let entries: fs.Dirent[];
		try {
			entries = await fs.promises.readdir(dir, { withFileTypes: true });
		} catch {
			return;
		}
		entries.sort((a, b) => a.name.localeCompare(b.name));
		for (const entry of entries) {
			if (found.length >= limit) {
				return;
			}
			const absolute = path.join(dir, entry.name);
			const relative = toPosix(path.relative(base, absolute));
			let isDirectory = entry.isDirectory();
			if (entry.isSymbolicLink()) {
				isDirectory = await fs.promises.stat(absolute).then(stat => stat.isDirectory(), () => false);
			}
			if (isDirectory) {
				// "dir/" against the exclude: '**/node_modules/**' prunes the whole subtree
				if (!excludeMatcher || (!excludeMatcher.test(relative) && !excludeMatcher.test(`${relative}/`))) {
					await walk(base, absolute);
				}
			} else if (spec.matcher.test(relative) && !excludeMatcher?.test(relative)) {
				found.push(Uri.file(absolute));
			}
		}
	};
	for (const base of spec.bases) {
		await walk(base, base);
	}
	return found;
};

// ------------------------------------------------------ text documents

function openTextDocument (uri: api.Uri): Thenable<api.TextDocument>;
function openTextDocument (fileName: string): Thenable<api.TextDocument>;
function openTextDocument (options?: { language?: string; content?: string }): Thenable<api.TextDocument>;
async function openTextDocument (target?: api.Uri | string | { language?: string; content?: string }): Promise<api.TextDocument> {
	if (target === undefined || (typeof target === 'object' && !(target instanceof Uri))) {
		const options = target as { language?: string; content?: string } | undefined;
		const document = new SnapshotTextDocument(Uri.from({ scheme: 'untitled', path: 'Untitled-1' }), options?.content ?? '', options?.language ?? 'plaintext');
		return document;
	}
	const fsPath = typeof target === 'string' ? target : target.fsPath;
	if (typeof target !== 'string' && target.scheme !== 'file') {
		warnUnsupported(`openTextDocument for scheme '${target.scheme}'`);
	}
	try {
		const document = SnapshotTextDocument.fromFile(fsPath);
		return document;
	} catch (error) {
		throw new Error(`cannot open ${fsPath}: ${(error as NodeJS.ErrnoException).code ?? String(error)}`);
	}
}

// ----------------------------------------------------------- namespace

type usedWorkspace = Pick<typeof api.workspace,
	| 'workspaceFolders'
	| 'onDidChangeWorkspaceFolders'
	| 'getWorkspaceFolder'
	| 'getConfiguration'
	| 'onDidChangeConfiguration'
	| 'createFileSystemWatcher'
	| 'findFiles'
	| 'openTextDocument'>;

export const workspace: usedWorkspace = {
	get workspaceFolders (): readonly api.WorkspaceFolder[] | undefined {
		return folders.length > 0 ? folders : undefined;
	},
	onDidChangeWorkspaceFolders: onDidChangeWorkspaceFoldersEmitter.event,
	getWorkspaceFolder (uri: api.Uri): api.WorkspaceFolder | undefined {
		const candidates = folders.filter(folder => isInside(uri.fsPath, folder.uri.fsPath));
		return candidates.sort((a, b) => b.uri.fsPath.length - a.uri.fsPath.length)[0];
	},
	getConfiguration,
	onDidChangeConfiguration: onDidChangeConfigurationEmitter.event,
	createFileSystemWatcher,
	findFiles,
	openTextDocument
};
