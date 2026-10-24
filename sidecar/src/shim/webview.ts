/**
 * Webview panels. The host opens a JCEF editor tab per panel; html and
 * messages travel as notifications. Everything a panel sends waits for
 * its `webview/create` request to settle, so the host never sees html
 * for a tab it has not opened yet.
 */
import type * as api from 'vscode-api';
import { log, rpc } from '../host';
import {
	RESOURCE_AUTHORITY,
	RESOURCE_SCHEME,
	WebviewCreateRequest,
	WebviewDisposeNotification,
	WebviewPostMessageNotification,
	WebviewRevealNotification,
	WebviewSetHtmlNotification,
	WebviewSetTitleNotification,
	type WebviewMessageParams,
	type WebviewPanelParams,
	type WebviewVisibilityParams
} from '../protocol';
import { EventEmitter, Uri, ViewColumn } from './types';

const panels = new Map<string, WebviewPanelImpl>();
let panelCounter = 0;

type panelOptions = api.WebviewPanelOptions & api.WebviewOptions;

class WebviewImpl implements api.Webview {
	private currentHtml = '';
	readonly onDidReceiveMessageEmitter = new EventEmitter<any>();
	readonly onDidReceiveMessage = this.onDidReceiveMessageEmitter.event;
	readonly cspSource = `${RESOURCE_SCHEME}:`;

	constructor (private readonly panel: WebviewPanelImpl, public options: api.WebviewOptions) {}

	get html (): string {
		return this.currentHtml;
	}

	set html (value: string) {
		this.currentHtml = value;
		this.panel.send(WebviewSetHtmlNotification, { panelId: this.panel.panelId, html: value });
	}

	postMessage (message: any): Thenable<boolean> {
		if (this.panel.isDisposed) {
			return Promise.resolve(false);
		}
		this.panel.send(WebviewPostMessageNotification, { panelId: this.panel.panelId, message });
		return Promise.resolve(true);
	}

	asWebviewUri (localResource: Uri): Uri {
		const uri = Uri.from({ scheme: RESOURCE_SCHEME, authority: RESOURCE_AUTHORITY, path: Uri.file(localResource.fsPath).path });
		return uri;
	}
}

class WebviewPanelImpl implements api.WebviewPanel {
	readonly panelId = `webview:${++panelCounter}`;
	readonly webview: WebviewImpl;
	iconPath?: Uri | { readonly light: Uri; readonly dark: Uri };
	isDisposed = false;
	private currentTitle: string;
	private column: api.ViewColumn | undefined;
	private isActive = true;
	private isVisible = true;
	private created: Promise<unknown>;
	private readonly onDidChangeViewStateEmitter = new EventEmitter<api.WebviewPanelOnDidChangeViewStateEvent>();
	private readonly onDidDisposeEmitter = new EventEmitter<void>();
	readonly onDidChangeViewState = this.onDidChangeViewStateEmitter.event;
	readonly onDidDispose = this.onDidDisposeEmitter.event;

	constructor (readonly viewType: string, title: string, column: api.ViewColumn | undefined, readonly options: panelOptions) {
		this.currentTitle = title;
		this.column = column;
		this.webview = new WebviewImpl(this, options);
		this.created = rpc().sendRequest(WebviewCreateRequest, {
			panelId: this.panelId,
			viewType,
			title,
			viewColumn: column,
			enableScripts: options.enableScripts ?? false,
			retainContextWhenHidden: options.retainContextWhenHidden ?? false,
			localResourceRoots: (options.localResourceRoots ?? []).map(root => root.fsPath)
		}).catch((error: unknown) => {
			log.error(`webview/create failed for ${this.panelId}: ${String(error)}`);
		});
	}

	/** Queue a notification behind webview/create, preserving order */
	send<P> (type: { method: string }, params: P): void {
		if (this.isDisposed) {
			return;
		}
		this.created = this.created.then(() => rpc().sendNotification(type.method, params)).catch((error: unknown) => {
			log.error(`${type.method} failed for ${this.panelId}: ${String(error)}`);
		});
	}

	get title (): string {
		return this.currentTitle;
	}

	set title (value: string) {
		this.currentTitle = value;
		this.send(WebviewSetTitleNotification, { panelId: this.panelId, title: value });
	}

	get viewColumn (): api.ViewColumn | undefined {
		return this.column;
	}

	get active (): boolean {
		return this.isActive;
	}

	get visible (): boolean {
		return this.isVisible;
	}

	reveal (viewColumn?: api.ViewColumn, preserveFocus?: boolean): void {
		if (viewColumn !== undefined) {
			this.column = viewColumn;
		}
		this.send(WebviewRevealNotification, { panelId: this.panelId, viewColumn, preserveFocus });
	}

	/** Disposed by the extension: close the tab on the host side */
	dispose (): void {
		if (this.isDisposed) {
			return;
		}
		this.send(WebviewDisposeNotification, { panelId: this.panelId });
		this.markDisposed();
	}

	/** Disposed from either side: fire onDidDispose exactly once */
	markDisposed (): void {
		if (this.isDisposed) {
			return;
		}
		this.isDisposed = true;
		panels.delete(this.panelId);
		this.onDidDisposeEmitter.fire();
		this.onDidDisposeEmitter.dispose();
		this.onDidChangeViewStateEmitter.dispose();
		this.webview.onDidReceiveMessageEmitter.dispose();
	}

	viewStateChanged (visible: boolean, active: boolean): void {
		this.isVisible = visible;
		this.isActive = active;
		this.onDidChangeViewStateEmitter.fire({ webviewPanel: this });
	}
}

export const createWebviewPanel = (
	viewType: string,
	title: string,
	showOptions: api.ViewColumn | { readonly viewColumn: api.ViewColumn; readonly preserveFocus?: boolean },
	options?: panelOptions
): WebviewPanelImpl => {
	const column = typeof showOptions === 'object' ? showOptions.viewColumn : showOptions;
	const panel = new WebviewPanelImpl(viewType, title, column ?? ViewColumn.One, options ?? {});
	panels.set(panel.panelId, panel);
	return panel;
};

/** Host → sidecar webview handlers */
export const webviewHost = {
	message: (params: WebviewMessageParams) => panels.get(params.panelId)?.webview.onDidReceiveMessageEmitter.fire(params.message),
	visibilityChanged: (params: WebviewVisibilityParams) => panels.get(params.panelId)?.viewStateChanged(params.visible, params.active),
	disposed: (params: WebviewPanelParams) => panels.get(params.panelId)?.markDisposed()
};
