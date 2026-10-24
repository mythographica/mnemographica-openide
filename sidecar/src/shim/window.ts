/**
 * `vscode.window`: every user-facing call becomes a host request or
 * notification. Text editors are snapshots: the IDE owns the real ones.
 */
import * as fs from 'fs';
import type * as api from 'vscode-api';
import { log, rpc, warnUnsupported } from '../host';
import {
	OutputAppendNotification,
	OutputShowNotification,
	ProgressBeginNotification,
	ProgressEndNotification,
	ProgressReportNotification,
	ShowMessageRequest,
	ShowOpenDialogRequest,
	ShowQuickPickRequest,
	ShowSaveDialogRequest,
	ShowTextDocumentRequest,
	StatusBarUpdateNotification,
	type Range as WireRange,
	type ShowMessageParams,
	type ShowTextDocumentParams
} from '../protocol';
import { SnapshotTextDocument } from './document';
import { createTreeView } from './tree';
import {
	CancellationTokenSource,
	Disposable,
	fromWireRange,
	neverEvent,
	Selection,
	StatusBarAlignment,
	toWireRange,
	Uri,
	ViewColumn
} from './types';
import { createWebviewPanel } from './webview';

// ------------------------------------------------------------- messages

type messageItem = string | api.MessageItem;

const showMessage = async (type: ShowMessageParams['type'], message: string, rest: unknown[]): Promise<any> => {
	let modal = false;
	let items = rest;
	const first = rest[0];
	if (first && typeof first === 'object' && !('title' in first)) {
		const options = first as api.MessageOptions;
		modal = options.modal ?? false;
		if (options.detail) {
			message = `${message}\n\n${options.detail}`;
		}
		items = rest.slice(1);
	}
	const choices = items.filter((item): item is messageItem => typeof item === 'string' || (typeof item === 'object' && item !== null && 'title' in item));
	const titles = choices.map(item => (typeof item === 'string' ? item : item.title));
	const picked = await rpc().sendRequest(ShowMessageRequest, { type, message, items: titles, modal });
	if (picked === null || picked === undefined) {
		return undefined;
	}
	return choices[titles.indexOf(picked)];
};

// ------------------------------------------------------------ quick pick

const showQuickPick = async (
	itemsOrPromise: readonly (string | api.QuickPickItem)[] | Thenable<readonly (string | api.QuickPickItem)[]>,
	options?: api.QuickPickOptions,
	_token?: api.CancellationToken
): Promise<any> => {
	const items = await itemsOrPromise;
	const wireItems = items.map((item) => (typeof item === 'string'
		? { label: item }
		: { label: item.label, description: item.description, detail: item.detail }));
	const picked = await rpc().sendRequest(ShowQuickPickRequest, {
		items: wireItems,
		placeHolder: options?.placeHolder ?? options?.title,
		canPickMany: options?.canPickMany ?? false
	});
	if (!picked) {
		return undefined;
	}
	const chosen = picked.map(index => items[index]).filter(item => item !== undefined);
	return options?.canPickMany ? chosen : chosen[0];
};

// --------------------------------------------------------------- editors

type editorSnapshot = { path: string; selection: Selection };

let activeEditor: editorSnapshot | undefined;

class SnapshotTextEditor implements api.TextEditor {
	private documentCache: api.TextDocument | undefined;
	private currentSelection: Selection;
	visibleRanges: readonly api.Range[] = [];
	options: api.TextEditorOptions = {};
	readonly viewColumn = ViewColumn.One;

	constructor (private readonly fsPath: string, selection: Selection, document?: api.TextDocument, private readonly onSelect?: (selection: Selection, reveal: boolean) => void) {
		this.currentSelection = selection;
		this.documentCache = document;
	}

	get document (): api.TextDocument {
		if (!this.documentCache) {
			this.documentCache = fs.existsSync(this.fsPath)
				? SnapshotTextDocument.fromFile(this.fsPath)
				: new SnapshotTextDocument(Uri.file(this.fsPath), '');
		}
		return this.documentCache;
	}

	get selection (): Selection {
		return this.currentSelection;
	}

	set selection (value: Selection) {
		this.currentSelection = value;
		this.onSelect?.(value, false);
	}

	get selections (): readonly Selection[] {
		return [this.currentSelection];
	}

	set selections (value: readonly Selection[]) {
		if (value[0]) {
			this.selection = value[0];
		}
	}

	revealRange (range: api.Range, _revealType?: api.TextEditorRevealType): void {
		this.onSelect?.(new Selection(range.start, range.end), true);
	}

	edit (): Thenable<boolean> {
		warnUnsupported('TextEditor.edit');
		return Promise.resolve(false);
	}

	insertSnippet (): Thenable<boolean> {
		warnUnsupported('TextEditor.insertSnippet');
		return Promise.resolve(false);
	}

	setDecorations (): void {
		warnUnsupported('TextEditor.setDecorations');
	}

	show (): void {
		showPath(this.fsPath, { selection: toWireRange(this.currentSelection) }).catch((error: unknown) => log.warn(`window/showTextDocument failed: ${String(error)}`));
	}

	hide (): void {
		warnUnsupported('TextEditor.hide');
	}
}

const showPath = (fsPath: string, params: Omit<ShowTextDocumentParams, 'path'>): Promise<null> =>
	rpc().sendRequest(ShowTextDocumentRequest, { path: fsPath, ...params });

/**
 * One `window/showTextDocument` request per navigation: the request is
 * sent on the next macrotask, so a caller that sets `editor.selection` /
 * calls `revealRange` right after awaiting showTextDocument (the
 * NavigationAdapter pattern) lands in the same request. Later selection
 * changes send a follow-up request that keeps the focus where it is.
 */
const showTextDocument = (target: api.TextDocument | api.Uri, columnOrOptions?: api.ViewColumn | api.TextDocumentShowOptions, preserveFocus?: boolean): Promise<api.TextEditor> => {
	const document = target instanceof Uri ? undefined : target as api.TextDocument;
	const fsPath = document ? document.uri.fsPath : (target as Uri).fsPath;
	const options = typeof columnOrOptions === 'object' ? columnOrOptions : { viewColumn: columnOrOptions, preserveFocus };
	const pending: ShowTextDocumentParams = { path: fsPath, preserveFocus: options.preserveFocus };
	if (options.viewColumn !== undefined && options.viewColumn > 0) {
		pending.viewColumn = options.viewColumn;
	}
	let selection = options.selection ? new Selection(options.selection.start, options.selection.end) : new Selection(0, 0, 0, 0);
	if (options.selection) {
		pending.selection = toWireRange(options.selection);
	}
	let flushed = false;
	const onSelect = (value: Selection, _reveal: boolean) => {
		selection = value;
		activeEditor = { path: fsPath, selection };
		const range: WireRange = toWireRange(value);
		if (!flushed) {
			pending.selection = range;
			return;
		}
		showPath(fsPath, { selection: range, preserveFocus: true }).catch((error: unknown) => log.warn(`window/showTextDocument failed: ${String(error)}`));
	};
	const editor = new SnapshotTextEditor(fsPath, selection, document, onSelect);
	activeEditor = { path: fsPath, selection };
	const sent = new Promise<null>((resolve, reject) => {
		setImmediate(() => {
			flushed = true;
			showPath(fsPath, pending).then(resolve, reject);
		});
	});
	// The editor is usable at once; a host failure surfaces in the log
	sent.catch((error: unknown) => log.warn(`window/showTextDocument failed for ${fsPath}: ${String(error)}`));
	return Promise.resolve(editor);
};

/** editor/activeChanged: the IDE's focused editor */
export const setActiveEditor = (fsPath: string | null, selection?: WireRange): void => {
	activeEditor = fsPath ? { path: fsPath, selection: selection ? toSelection(selection) : new Selection(0, 0, 0, 0) } : undefined;
};

const toSelection = (range: WireRange): Selection => {
	const value = fromWireRange(range);
	return new Selection(value.start, value.end);
};

// ------------------------------------------------------------ status bar

let statusBarCounter = 0;

class StatusBarItemImpl implements api.StatusBarItem {
	name: string | undefined;
	color: string | api.ThemeColor | undefined;
	backgroundColor: api.ThemeColor | undefined;
	accessibilityInformation: api.AccessibilityInformation | undefined;
	private state = { text: '', tooltip: undefined as string | api.MarkdownString | undefined, command: undefined as string | api.Command | undefined };
	private shown = false;
	private scheduled = false;

	constructor (readonly id: string, readonly alignment: api.StatusBarAlignment, readonly priority: number | undefined) {}

	get text (): string { return this.state.text; }
	set text (value: string) { this.state.text = value; this.schedule(); }
	get tooltip (): string | api.MarkdownString | undefined { return this.state.tooltip; }
	set tooltip (value: string | api.MarkdownString | undefined) { this.state.tooltip = value; this.schedule(); }
	get command (): string | api.Command | undefined { return this.state.command; }
	set command (value: string | api.Command | undefined) { this.state.command = value; this.schedule(); }

	show (): void { this.shown = true; this.schedule(); }
	hide (): void { this.shown = false; this.schedule(); }
	dispose (): void { this.hide(); }

	// Property assignments in a row collapse into one statusBar/update
	private schedule (): void {
		if (this.scheduled) {
			return;
		}
		this.scheduled = true;
		queueMicrotask(() => {
			this.scheduled = false;
			const tooltip = this.state.tooltip;
			const command = this.state.command;
			rpc().sendNotification(StatusBarUpdateNotification, {
				id: this.id,
				text: this.state.text,
				tooltip: typeof tooltip === 'string' ? tooltip : tooltip?.value,
				command: typeof command === 'string' ? command : command?.command,
				visible: this.shown,
				alignment: this.alignment === StatusBarAlignment.Right ? 'right' : 'left',
				priority: this.priority
			}).catch(() => undefined);
		});
	}
}

const createStatusBarItem = (idOrAlignment?: string | api.StatusBarAlignment, alignmentOrPriority?: api.StatusBarAlignment | number, priority?: number): StatusBarItemImpl => {
	const explicitId = typeof idOrAlignment === 'string' ? idOrAlignment : undefined;
	const alignment = (typeof idOrAlignment === 'string' ? alignmentOrPriority : idOrAlignment) ?? StatusBarAlignment.Left;
	const order = typeof idOrAlignment === 'string' ? priority : alignmentOrPriority;
	const item = new StatusBarItemImpl(explicitId ?? `statusBar:${++statusBarCounter}`, alignment, order);
	return item;
};

// --------------------------------------------------------------- output

class OutputChannelImpl implements api.LogOutputChannel {
	readonly logLevel = 2 as api.LogLevel; // LogLevel.Debug
	readonly onDidChangeLogLevel: api.Event<api.LogLevel> = neverEvent;

	constructor (readonly name: string) {}

	append (value: string): void {
		rpc().sendNotification(OutputAppendNotification, { channel: this.name, text: value }).catch(() => undefined);
	}

	appendLine (value: string): void {
		this.append(`${value}\n`);
	}

	replace (value: string): void {
		warnUnsupported('OutputChannel.replace (appends instead)');
		this.append(value);
	}

	clear (): void {
		warnUnsupported('OutputChannel.clear');
	}

	show (columnOrPreserveFocus?: api.ViewColumn | boolean, preserveFocus?: boolean): void {
		const keepFocus = typeof columnOrPreserveFocus === 'boolean' ? columnOrPreserveFocus : preserveFocus;
		rpc().sendNotification(OutputShowNotification, { channel: this.name, preserveFocus: keepFocus }).catch(() => undefined);
	}

	hide (): void {
		warnUnsupported('OutputChannel.hide');
	}

	dispose (): void {
		// the host keeps the console tab; nothing to release here
	}

	private line (level: string, message: string | Error, args: unknown[]): void {
		const text = message instanceof Error ? message.stack ?? message.message : message;
		this.appendLine([`[${level}] ${text}`, ...args.map(String)].join(' '));
	}

	trace (message: string, ...args: any[]): void { this.line('trace', message, args); }
	debug (message: string, ...args: any[]): void { this.line('debug', message, args); }
	info (message: string, ...args: any[]): void { this.line('info', message, args); }
	warn (message: string, ...args: any[]): void { this.line('warning', message, args); }
	error (error: string | Error, ...args: any[]): void { this.line('error', error, args); }
}

// -------------------------------------------------------------- progress

let progressCounter = 0;
const progressTokens = new Map<string, CancellationTokenSource>();

const withProgress = async <R>(
	options: api.ProgressOptions,
	task: (progress: api.Progress<{ message?: string; increment?: number }>, token: api.CancellationToken) => Thenable<R>
): Promise<R> => {
	const id = `progress:${++progressCounter}`;
	const source = new CancellationTokenSource();
	progressTokens.set(id, source);
	await rpc().sendNotification(ProgressBeginNotification, { id, title: options.title, cancellable: options.cancellable ?? false });
	try {
		const result = await task({
			report: (value) => {
				rpc().sendNotification(ProgressReportNotification, { id, message: value.message, increment: value.increment }).catch(() => undefined);
			}
		}, source.token);
		return result;
	} finally {
		progressTokens.delete(id);
		source.dispose();
		await rpc().sendNotification(ProgressEndNotification, { id });
	}
};

/** progress/cancelled from the host */
export const cancelProgress = (id: string): void => {
	progressTokens.get(id)?.cancel();
};

// ----------------------------------------------------------- uri handler

let uriHandler: api.UriHandler | undefined;

/** uri/handle from the host */
export const handleUri = async (value: string): Promise<void> => {
	if (!uriHandler) {
		log.warn(`uri/handle ${value}: no handler registered`);
		return;
	}
	await uriHandler.handleUri(Uri.parse(value));
};

// --------------------------------------------------------------- dialogs

const showOpenDialog = async (options?: api.OpenDialogOptions): Promise<Uri[] | undefined> => {
	const picked = await rpc().sendRequest(ShowOpenDialogRequest, {
		canSelectFiles: options?.canSelectFiles ?? true,
		canSelectFolders: options?.canSelectFolders ?? false,
		canSelectMany: options?.canSelectMany ?? false,
		title: options?.title,
		defaultPath: options?.defaultUri?.fsPath,
		openLabel: options?.openLabel
	});
	return picked ? picked.map(item => Uri.file(item)) : undefined;
};

const showSaveDialog = async (options?: api.SaveDialogOptions): Promise<Uri | undefined> => {
	const picked = await rpc().sendRequest(ShowSaveDialogRequest, {
		defaultPath: options?.defaultUri?.fsPath,
		title: options?.title ?? options?.saveLabel,
		filters: options?.filters
	});
	return picked ? Uri.file(picked) : undefined;
};

// ----------------------------------------------------------- namespace

type usedWindow = Pick<typeof api.window,
	| 'activeTextEditor'
	| 'createOutputChannel'
	| 'createStatusBarItem'
	| 'createTreeView'
	| 'createWebviewPanel'
	| 'registerUriHandler'
	| 'showErrorMessage'
	| 'showInformationMessage'
	| 'showOpenDialog'
	| 'showQuickPick'
	| 'showSaveDialog'
	| 'showTextDocument'
	| 'showWarningMessage'
	| 'withProgress'>;

export const window: usedWindow = {
	get activeTextEditor (): api.TextEditor | undefined {
		const snapshot = activeEditor;
		return snapshot ? new SnapshotTextEditor(snapshot.path, snapshot.selection) : undefined;
	},
	showInformationMessage: (message: string, ...rest: any[]) => showMessage('info', message, rest),
	showWarningMessage: (message: string, ...rest: any[]) => showMessage('warning', message, rest),
	showErrorMessage: (message: string, ...rest: any[]) => showMessage('error', message, rest),
	showQuickPick,
	showOpenDialog,
	showSaveDialog,
	showTextDocument,
	createTreeView,
	createWebviewPanel,
	createStatusBarItem,
	createOutputChannel: (name: string, _languageIdOrOptions?: string | { log: true }) => new OutputChannelImpl(name),
	withProgress,
	registerUriHandler (handler: api.UriHandler): Disposable {
		uriHandler = handler;
		return new Disposable(() => {
			if (uriHandler === handler) {
				uriHandler = undefined;
			}
		});
	}
};
