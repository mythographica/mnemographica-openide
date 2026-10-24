/**
 * The ExtensionContext handed to activate(): paths from `initialize`,
 * Mementos persisted as JSON files under storagePath.
 */
import * as fs from 'fs';
import * as path from 'path';
import type * as api from 'vscode-api';
import { log, warnUnsupported } from './host';
import type { InitializeParams } from './protocol';
import { EventEmitter, Uri } from './shim/types';

class FileMemento implements api.Memento {
	private values: Record<string, unknown> = {};

	constructor (private readonly file: string) {
		try {
			this.values = JSON.parse(fs.readFileSync(file, 'utf8')) as Record<string, unknown>;
		} catch (error) {
			if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
				log.warn(`state file ${file} unreadable, starting empty: ${String(error)}`);
			}
		}
	}

	keys (): readonly string[] {
		return Object.keys(this.values);
	}

	get<T> (key: string, defaultValue?: T): T | undefined {
		return key in this.values ? this.values[key] as T : defaultValue;
	}

	async update (key: string, value: unknown): Promise<void> {
		if (value === undefined) {
			delete this.values[key];
		} else {
			this.values[key] = value;
		}
		await fs.promises.mkdir(path.dirname(this.file), { recursive: true });
		await fs.promises.writeFile(this.file, JSON.stringify(this.values, null, '\t'), 'utf8');
	}

	setKeysForSync (_keys: readonly string[]): void {
		// settings sync does not exist in the IDE; nothing to record
	}
}

// Secrets are not persisted: the IDE's password safe is not bridged in v1
const sessionSecrets = (): api.SecretStorage => {
	const values = new Map<string, string>();
	const changes = new EventEmitter<api.SecretStorageChangeEvent>();
	return {
		get: (key) => {
			warnUnsupported('ExtensionContext.secrets (kept in memory only)');
			return Promise.resolve(values.get(key));
		},
		store: (key, value) => {
			warnUnsupported('ExtensionContext.secrets (kept in memory only)');
			values.set(key, value);
			changes.fire({ key });
			return Promise.resolve();
		},
		delete: (key) => {
			values.delete(key);
			changes.fire({ key });
			return Promise.resolve();
		},
		onDidChange: changes.event
	};
};

const noEnvironmentVariables = (): api.EnvironmentVariableCollection => {
	const ignore = () => warnUnsupported('ExtensionContext.environmentVariableCollection');
	return {
		persistent: false,
		replace: ignore,
		append: ignore,
		prepend: ignore,
		get: () => undefined,
		forEach: () => undefined,
		delete: ignore,
		clear: ignore,
		[Symbol.iterator]: function * () { /* no variables */ }
	};
};

export type packageJson = {
	name: string;
	publisher: string;
	version: string;
	contributes?: Record<string, unknown>;
	[key: string]: unknown;
};

export const createExtensionContext = (params: InitializeParams, manifest: packageJson): api.ExtensionContext => {
	const extensionUri = Uri.file(params.extensionPath);
	const storageUri = Uri.file(params.storagePath);
	const logPath = path.join(params.storagePath, 'logs');
	const extension: api.Extension<unknown> = {
		id: `${manifest.publisher}.${manifest.name}`,
		extensionUri,
		extensionPath: params.extensionPath,
		isActive: true,
		packageJSON: manifest,
		extensionKind: 2 as api.ExtensionKind, // ExtensionKind.Workspace
		exports: undefined,
		activate: () => Promise.resolve(undefined)
	};
	const context: api.ExtensionContext = {
		subscriptions: [],
		workspaceState: new FileMemento(path.join(params.storagePath, 'workspaceState.json')),
		globalState: new FileMemento(path.join(params.storagePath, 'globalState.json')),
		secrets: sessionSecrets(),
		extensionUri,
		extensionPath: params.extensionPath,
		environmentVariableCollection: noEnvironmentVariables(),
		asAbsolutePath: (relativePath: string) => path.join(params.extensionPath, relativePath),
		storageUri,
		storagePath: params.storagePath,
		globalStorageUri: storageUri,
		globalStoragePath: params.storagePath,
		logUri: Uri.file(logPath),
		logPath,
		extensionMode: 1 as api.ExtensionMode, // ExtensionMode.Production
		extension
	};
	return context;
};
