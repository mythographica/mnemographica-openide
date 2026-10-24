/**
 * Host ↔ Sidecar wire contract — the typed source of truth for
 * docs/PROTOCOL.md. Every message the two halves exchange is declared
 * here once, as a vscode-jsonrpc RequestType / NotificationType; the
 * Kotlin data classes in rpc/Protocol.kt mirror these field by field.
 *
 * Conventions: paths are plain absolute filesystem paths (never file://
 * URIs); positions are zero-based. `→` request, `⇒` notification.
 *
 * Members marked `PROPOSED` are not in PROTOCOL.md yet: the sidecar
 * tolerates their absence on the host side.
 */
import { NotificationType, RequestType, RequestType0 } from 'vscode-jsonrpc/node';

// ---------------------------------------------------------------- shared

export interface Position {
	line: number;
	character: number;
}

export interface Range {
	start: Position;
	end: Position;
}

export interface Location {
	path: string;
	range: Range;
}

// ------------------------------------------------------------- lifecycle

export interface InitializeParams {
	/** Content roots of the IDE project (vscode.workspace.workspaceFolders) */
	projectRoots: string[];
	/** Directory holding the extension's media/ and package.json */
	extensionPath: string;
	/** Flat "mnemographica.<key>" → value, every key with its current value */
	config: Record<string, unknown>;
	ideVersion: string;
	pluginVersion: string;
	/** Writable per-project directory (globalState/workspaceState, storageUri) */
	storagePath: string;
	theme: 'dark' | 'light';
}

export interface CommandInfo {
	command: string;
	title: string;
	category?: string;
	/** "$(refresh)" codicon id or a path relative to extensionPath */
	icon?: string;
	/**
	 * PROPOSED: true for commands the extension registered without
	 * contributing them in package.json (tree-item click handlers and the
	 * like). VS Code never lists those in the command palette; the host
	 * should not offer them as standalone actions.
	 */
	internal?: boolean;
}

export interface ViewInfo {
	id: string;
	name: string;
	containerId: string;
	containerTitle: string;
}

export interface MenuItem {
	command: string;
	when?: string;
	group?: string;
}

/** contributes.menus verbatim: view/title, view/item/context, commandPalette */
export type MenuContribution = Record<string, MenuItem[]>;

export interface ConfigurationProperty {
	type?: string | string[];
	default?: unknown;
	description?: string;
	markdownDescription?: string;
	enum?: unknown[];
	enumDescriptions?: string[];
	[key: string]: unknown;
}

/** contributes.configuration.properties verbatim, keyed by the full "mnemographica.<key>" */
export type ConfigurationSchema = Record<string, ConfigurationProperty>;

export interface InitializeResult {
	commands: CommandInfo[];
	views: ViewInfo[];
	menus: MenuContribution;
	configuration: ConfigurationSchema;
}

// host → sidecar
export const InitializeRequest = new RequestType<InitializeParams, InitializeResult, void>('initialize');
export const InitializedNotification = new NotificationType<Record<string, never> | undefined>('initialized');
export const ShutdownRequest = new RequestType0<null, void>('shutdown');

// ------------------------------------------------------------------ tree

export interface TreeNode {
	/** Sidecar-assigned ("<viewId>:<n>"), stable while the element is alive */
	id: string;
	label: string;
	description?: string;
	/** Plain text or markdown */
	tooltip?: string;
	/** Codicon id ("refresh", "symbol-class") */
	iconId?: string;
	/** Absolute path to an svg/png; wins over iconId */
	iconPath?: string;
	/** vscode.ThemeColor id set on the ThemeIcon ("errorForeground") */
	iconColor?: string;
	/** 0 None | 1 Collapsed | 2 Expanded */
	collapsibleState: 0 | 1 | 2;
	contextValue?: string;
	command?: { command: string; arguments?: unknown[] };
	resourcePath?: string;
}

/** A tree element, addressed by its view and node id */
export interface TreeItemRef {
	viewId: string;
	itemId: string;
}

/**
 * Sidecar-internal: TreeNode.command.arguments that are not plain JSON
 * (class instances, cycles) travel as this token and are swapped back for
 * the original value when the host sends them back in `command/execute`.
 * The host treats it as opaque data.
 */
export interface ArgumentRef {
	$mnemoRef: string;
}

export interface GetChildrenParams { viewId: string; itemId: string | null; }
export interface GetParentParams { viewId: string; itemId: string; }
export interface SelectionChangedParams { viewId: string; itemIds: string[]; }
export interface VisibilityChangedParams { viewId: string; visible: boolean; }
export interface ExpansionChangedParams { viewId: string; itemId: string; expanded: boolean; }

export const TreeGetChildrenRequest = new RequestType<GetChildrenParams, TreeNode[], void>('tree/getChildren');
export const TreeGetParentRequest = new RequestType<GetParentParams, string | null, void>('tree/getParent');
export const TreeSelectionChangedNotification = new NotificationType<SelectionChangedParams>('tree/selectionChanged');
export const TreeVisibilityChangedNotification = new NotificationType<VisibilityChangedParams>('tree/visibilityChanged');
export const TreeExpansionChangedNotification = new NotificationType<ExpansionChangedParams>('tree/expansionChanged');

export interface TreeRegisteredParams { viewId: string; showCollapseAll?: boolean; canSelectMany?: boolean; }
export interface TreeInvalidateParams { viewId: string; itemId: string | null; }
export interface TreeRevealParams { viewId: string; itemId: string; select: boolean; focus: boolean; expand: boolean | number; }
export interface TreeSetMetaParams {
	viewId: string;
	title?: string;
	description?: string;
	message?: string;
	badge?: { value: number; tooltip: string };
}

// sidecar → host
export const TreeRegisteredNotification = new NotificationType<TreeRegisteredParams>('tree/registered');
export const TreeInvalidateNotification = new NotificationType<TreeInvalidateParams>('tree/invalidate');
export const TreeRevealNotification = new NotificationType<TreeRevealParams>('tree/reveal');
export const TreeSetMetaNotification = new NotificationType<TreeSetMetaParams>('tree/setMeta');
export const ViewFocusNotification = new NotificationType<{ viewId: string }>('view/focus');

// -------------------------------------------------------------- commands

export interface CommandExecuteParams {
	command: string;
	args: unknown[];
	/**
	 * PROPOSED (WP1 host): the tree element a `view/item/context` command was
	 * invoked on. The sidecar passes the live element as the first argument,
	 * as VS Code does for item context menus.
	 */
	context?: TreeItemRef;
}
export interface ExecuteBuiltinParams { command: string; args: unknown[]; }

/** host → sidecar. Unknown command → JSON-RPC error -32601 */
export const CommandExecuteRequest = new RequestType<CommandExecuteParams, unknown, void>('command/execute');
/** sidecar → host. Unsupported command → JSON-RPC error -32601 */
export const ExecuteBuiltinRequest = new RequestType<ExecuteBuiltinParams, unknown, void>('commands/executeBuiltin');

// --------------------------------------------------------------- webview

export interface WebviewCreateParams {
	panelId: string;
	viewType: string;
	title: string;
	viewColumn?: number;
	enableScripts: boolean;
	retainContextWhenHidden: boolean;
	localResourceRoots: string[];
}
export interface WebviewHtmlParams { panelId: string; html: string; }
export interface WebviewMessageParams { panelId: string; message: unknown; }
export interface WebviewRevealParams { panelId: string; viewColumn?: number; preserveFocus?: boolean; }
export interface WebviewTitleParams { panelId: string; title: string; }
export interface WebviewPanelParams { panelId: string; }
export interface WebviewVisibilityParams { panelId: string; visible: boolean; active: boolean; }

// sidecar → host
export const WebviewCreateRequest = new RequestType<WebviewCreateParams, null, void>('webview/create');
export const WebviewSetHtmlNotification = new NotificationType<WebviewHtmlParams>('webview/setHtml');
export const WebviewPostMessageNotification = new NotificationType<WebviewMessageParams>('webview/postMessage');
export const WebviewRevealNotification = new NotificationType<WebviewRevealParams>('webview/reveal');
export const WebviewSetTitleNotification = new NotificationType<WebviewTitleParams>('webview/setTitle');
export const WebviewDisposeNotification = new NotificationType<WebviewPanelParams>('webview/dispose');
// host → sidecar
export const WebviewMessageNotification = new NotificationType<WebviewMessageParams>('webview/message');
export const WebviewVisibilityChangedNotification = new NotificationType<WebviewVisibilityParams>('webview/visibilityChanged');
export const WebviewDisposedNotification = new NotificationType<WebviewPanelParams>('webview/disposed');

/**
 * `Webview.asWebviewUri` result: `mnemo-res://file` + the absolute path,
 * each segment percent-encoded (spaces become %20). The host's scheme
 * handler decodes the path and serves it only under localResourceRoots.
 */
export const RESOURCE_SCHEME = 'mnemo-res';
export const RESOURCE_AUTHORITY = 'file';

// ------------------------------------------------------------ navigation

export interface DefinitionParams { path: string; position: Position; }
export interface ReferencesParams { path: string; position: Position; includeDeclaration: boolean; }

export const DefinitionProvideRequest = new RequestType<DefinitionParams, Location[], void>('definition/provide');
export const ReferencesProvideRequest = new RequestType<ReferencesParams, Location[], void>('references/provide');

// ------------------------------------------------------- config & editor

export const ConfigChangedNotification = new NotificationType<{ config: Record<string, unknown> }>('config/changed');
export const WorkspaceFoldersChangedNotification = new NotificationType<{ projectRoots: string[] }>('workspace/foldersChanged');
export const EditorActiveChangedNotification = new NotificationType<{ path: string | null; selection?: Range }>('editor/activeChanged');
export const UriHandleNotification = new NotificationType<{ uri: string }>('uri/handle');

// ---------------------------------------------------------------- window

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';
export interface ShowMessageParams { type: 'info' | 'warning' | 'error'; message: string; items: string[]; modal?: boolean; }
export interface QuickPickItemWire { label: string; description?: string; detail?: string; }
export interface ShowQuickPickParams { items: QuickPickItemWire[]; placeHolder?: string; canPickMany?: boolean; }
export interface ShowInputBoxParams { prompt?: string; value?: string; placeHolder?: string; password?: boolean; }
export interface ShowOpenDialogParams {
	canSelectFiles: boolean;
	canSelectFolders: boolean;
	canSelectMany: boolean;
	title?: string;
	defaultPath?: string;
	openLabel?: string;
}
export interface ShowSaveDialogParams { defaultPath?: string; title?: string; filters?: Record<string, string[]>; }
export interface ShowTextDocumentParams { path: string; selection?: Range; preserveFocus?: boolean; viewColumn?: number; }
export interface ProgressBeginParams { id: string; title?: string; cancellable?: boolean; }
export interface ProgressReportParams { id: string; message?: string; increment?: number; }
export interface StatusBarUpdateParams {
	id: string;
	text: string;
	tooltip?: string;
	command?: string;
	visible: boolean;
	alignment: 'left' | 'right';
	priority?: number;
}

// sidecar → host
export const LogNotification = new NotificationType<{ level: LogLevel; message: string }>('log');
export const ShowMessageRequest = new RequestType<ShowMessageParams, string | null, void>('window/showMessage');
export const ShowQuickPickRequest = new RequestType<ShowQuickPickParams, number[] | null, void>('window/showQuickPick');
export const ShowInputBoxRequest = new RequestType<ShowInputBoxParams, string | null, void>('window/showInputBox');
export const ShowOpenDialogRequest = new RequestType<ShowOpenDialogParams, string[] | null, void>('window/showOpenDialog');
export const ShowSaveDialogRequest = new RequestType<ShowSaveDialogParams, string | null, void>('window/showSaveDialog');
export const ShowTextDocumentRequest = new RequestType<ShowTextDocumentParams, null, void>('window/showTextDocument');
export const OpenExternalNotification = new NotificationType<{ url: string }>('window/openExternal');
export const ProgressBeginNotification = new NotificationType<ProgressBeginParams>('progress/begin');
export const ProgressReportNotification = new NotificationType<ProgressReportParams>('progress/report');
export const ProgressEndNotification = new NotificationType<{ id: string }>('progress/end');
export const StatusBarUpdateNotification = new NotificationType<StatusBarUpdateParams>('statusBar/update');
export const OutputAppendNotification = new NotificationType<{ channel: string; text: string }>('output/append');
export const OutputShowNotification = new NotificationType<{ channel: string; preserveFocus?: boolean }>('output/show');
export const FsWatchersNotification = new NotificationType<{ globs: string[] }>('fs/watchers');
// host → sidecar (optional)
export const ProgressCancelledNotification = new NotificationType<{ id: string }>('progress/cancelled');

/** JSON-RPC error code for unknown methods / commands */
export const METHOD_NOT_FOUND = -32601;
