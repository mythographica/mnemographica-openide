/**
 * Sidecar entry: JSON-RPC on stdio, the `vscode` shim, and the unmodified
 * mnemographica extension on top. See docs/PROTOCOL.md.
 */
import { rpcStdout } from './stdio'; // first: stdout belongs to RPC before anything can print
import * as fs from 'fs';
import * as path from 'path';
import { createMessageConnection, ErrorCodes, ResponseError, StreamMessageReader, StreamMessageWriter, Trace } from 'vscode-jsonrpc/node';
import type * as api from 'vscode-api';
import { models } from 'mnemographica:models';
import { createExtensionContext, type packageJson } from './context';
import { buildInitializeResult, configurationDefaults, contributedViews } from './contributes';
import { log, setConnection, setSession } from './host';
import {
	CommandExecuteRequest,
	ConfigChangedNotification,
	DefinitionProvideRequest,
	EditorActiveChangedNotification,
	InitializedNotification,
	InitializeRequest,
	ProgressCancelledNotification,
	ReferencesProvideRequest,
	ShutdownRequest,
	TreeExpansionChangedNotification,
	TreeGetChildrenRequest,
	TreeGetParentRequest,
	TreeSelectionChangedNotification,
	TreeVisibilityChangedNotification,
	UriHandleNotification,
	WebviewDisposedNotification,
	WebviewMessageNotification,
	WebviewVisibilityChangedNotification,
	WorkspaceFoldersChangedNotification,
	type InitializeParams,
	type InitializeResult
} from './protocol';
import { addKnownView, executeFromHost, registeredCommandIds } from './shim/commands';
import { provideDefinitions, provideReferences } from './shim/languages';
import { treeHost } from './shim/tree';
import { webviewHost } from './shim/webview';
import { cancelProgress, handleUri, setActiveEditor } from './shim/window';
import { closeWatchers, setConfiguration, setConfigurationDefaults, setWorkspaceFolders } from './shim/workspace';

type extensionModule = { activate: (context: api.ExtensionContext) => unknown; deactivate?: () => unknown };

/**
 * The extension's topologica bootstrap require()s every file under
 * `<extensionPath>/out/src/models`. The build writes one stub per model
 * there; each stub returns the model module bundled into this file, so
 * mnemonica, the collections and the logger stay single instances.
 */
const MODELS_HOOK = '__mnemographicaSidecarModels';
(globalThis as Record<string, unknown>)[MODELS_HOOK] = (name: string): unknown => {
	const load = models[name];
	if (!load) {
		throw new Error(`no bundled model '${name}' — rebuild the sidecar`);
	}
	return load();
};

// Stacks in logs and RPC error data point at the TypeScript sources (dist/main.js.map)
process.setSourceMapsEnabled(true);

const connection = createMessageConnection(new StreamMessageReader(process.stdin), new StreamMessageWriter(rpcStdout));
setConnection(connection);

if (process.env.MNEMO_SIDECAR_DEBUG) {
	connection.trace(Trace.Verbose, { log: (message: string, data?: string) => process.stderr.write(`[rpc] ${message}${data ? ` ${data}` : ''}\n`) }).catch(() => undefined);
}

process.on('unhandledRejection', (reason) => {
	log.error(`unhandled rejection: ${reason instanceof Error ? reason.stack ?? reason.message : String(reason)}`);
});
process.on('uncaughtException', (error) => {
	log.error(`uncaught exception: ${error.stack ?? error.message}`);
});

/** A thrown handler error reaches the host with its stack in `data` */
const guard = <P, R>(method: string, handler: (params: P) => R | Promise<R>) => async (params: P): Promise<R> => {
	try {
		const result = await handler(params);
		return result;
	} catch (error) {
		if (error instanceof ResponseError) {
			throw error;
		}
		const failure = error instanceof Error ? error : new Error(String(error));
		log.error(`${method} failed: ${failure.stack ?? failure.message}`);
		throw new ResponseError(ErrorCodes.InternalError, `${method}: ${failure.message}`, { stack: failure.stack });
	}
};

const notified = <P>(method: string, handler: (params: P) => unknown) => (params: P): void => {
	Promise.resolve()
		.then(() => handler(params))
		.catch((error: unknown) => log.error(`${method} failed: ${error instanceof Error ? error.stack ?? error.message : String(error)}`));
};

// ------------------------------------------------------------- lifecycle

let extension: extensionModule | undefined;
let context: api.ExtensionContext | undefined;
let stopped = false;

const readManifest = (extensionPath: string): packageJson => {
	const manifestPath = path.join(extensionPath, 'package.json');
	try {
		return JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as packageJson;
	} catch (error) {
		throw new Error(`extension manifest unreadable at ${manifestPath}: ${String(error)}`);
	}
};

const initialize = async (params: InitializeParams): Promise<InitializeResult> => {
	if (extension) {
		throw new ResponseError(ErrorCodes.InvalidRequest, 'initialize was already called');
	}
	setSession(params);
	const manifest = readManifest(params.extensionPath);
	const modelsDir = path.join(params.extensionPath, 'out', 'src', 'models');
	if (!fs.existsSync(modelsDir)) {
		throw new Error(`model stubs missing at ${modelsDir} — extensionPath must be the sidecar dist/ directory`);
	}
	setWorkspaceFolders(params.projectRoots);
	setConfigurationDefaults(configurationDefaults(manifest));
	setConfiguration(params.config ?? {}, false);
	for (const view of contributedViews(manifest)) {
		addKnownView(view.id);
	}
	fs.mkdirSync(params.storagePath, { recursive: true });
	context = createExtensionContext(params, manifest);
	log.info(`activating ${manifest.publisher}.${manifest.name}@${manifest.version} for ${params.projectRoots.join(', ') || '(no folders)'}`);
	extension = await import('../../mnemographica/src/extension') as extensionModule;
	await extension.activate(context);
	return buildInitializeResult(manifest, registeredCommandIds());
};

const stop = async (): Promise<void> => {
	if (stopped) {
		return;
	}
	stopped = true;
	try {
		await extension?.deactivate?.();
	} catch (error) {
		log.error(`deactivate failed: ${String(error)}`);
	}
	for (const subscription of context?.subscriptions ?? []) {
		try {
			subscription.dispose();
		} catch (error) {
			log.error(`dispose failed: ${String(error)}`);
		}
	}
	await closeWatchers();
};

// Exit once the shutdown response has left the pipe: the connection
// writes it a few ticks after the handler resolves, then both stdout
// buffers must drain
const exitAfterFlush = (code: number): void => {
	const check = (): void => {
		if (rpcStdout.writableLength === 0 && process.stdout.writableLength === 0) {
			process.exit(code);
		}
		setTimeout(check, 10);
	};
	setTimeout(check, 50);
};

connection.onRequest(InitializeRequest, guard('initialize', initialize));
connection.onNotification(InitializedNotification, () => log.debug('host initialized'));
connection.onRequest(ShutdownRequest, guard('shutdown', async () => {
	await stop();
	exitAfterFlush(0);
	return null;
}));
connection.onClose(() => {
	stop().finally(() => process.exit(0)).catch(() => undefined);
});

// ------------------------------------------------------------ host → sidecar

connection.onRequest(TreeGetChildrenRequest, guard('tree/getChildren', treeHost.getChildren));
connection.onRequest(TreeGetParentRequest, guard('tree/getParent', treeHost.getParent));
connection.onNotification(TreeSelectionChangedNotification, notified('tree/selectionChanged', treeHost.selectionChanged));
connection.onNotification(TreeVisibilityChangedNotification, notified('tree/visibilityChanged', treeHost.visibilityChanged));
connection.onNotification(TreeExpansionChangedNotification, notified('tree/expansionChanged', treeHost.expansionChanged));
connection.onRequest(CommandExecuteRequest, guard('command/execute', executeFromHost));
connection.onNotification(WebviewMessageNotification, notified('webview/message', webviewHost.message));
connection.onNotification(WebviewVisibilityChangedNotification, notified('webview/visibilityChanged', webviewHost.visibilityChanged));
connection.onNotification(WebviewDisposedNotification, notified('webview/disposed', webviewHost.disposed));
connection.onRequest(DefinitionProvideRequest, guard('definition/provide', provideDefinitions));
connection.onRequest(ReferencesProvideRequest, guard('references/provide', provideReferences));
connection.onNotification(ConfigChangedNotification, notified('config/changed', params => setConfiguration(params.config, true)));
connection.onNotification(WorkspaceFoldersChangedNotification, notified('workspace/foldersChanged', params => setWorkspaceFolders(params.projectRoots)));
connection.onNotification(EditorActiveChangedNotification, notified('editor/activeChanged', params => setActiveEditor(params.path, params.selection)));
connection.onNotification(UriHandleNotification, notified('uri/handle', params => handleUri(params.uri)));
connection.onNotification(ProgressCancelledNotification, notified('progress/cancelled', params => cancelProgress(params.id)));

connection.listen();
log.debug(`sidecar up (pid ${process.pid}, node ${process.version})`);
