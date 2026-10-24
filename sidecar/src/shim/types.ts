/**
 * Value types of the `vscode` module. Each class `implements` its real
 * @types/vscode declaration (re-published as 'vscode-api'), so the
 * extension's instances stay assignable wherever the API expects them.
 */
import * as nodePath from 'path';
import type * as api from 'vscode-api';

// ------------------------------------------------------------------ Uri

// The classes below add no members beyond their API declaration: values
// typed by the API (an api.Disposable from registerCommand, an api.Uri
// from showOpenDialog) must stay assignable to the shim's class types.

const URI_PATTERN = /^(([^:/?#]+?):)?(\/\/([^/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?/;
const isWindows = process.platform === 'win32';

const decode = (value: string): string => {
	try {
		return decodeURIComponent(value);
	} catch {
		return value;
	}
};

// Percent-encode everything but the RFC 3986 unreserved set and the
// characters a component keeps literally ('/' in paths, '=&' in queries)
const encodeComponent = (value: string, keep: string): string => {
	let out = '';
	for (const char of value) {
		if (/[A-Za-z0-9\-._~]/.test(char) || keep.includes(char)) {
			out += char;
		} else {
			out += encodeURIComponent(char);
		}
	}
	return out;
};

export class Uri implements api.Uri {
	static parse (value: string, _strict?: boolean): Uri {
		const match = URI_PATTERN.exec(value);
		if (!match) {
			return new Uri('', '', '', '', '');
		}
		const uri = new Uri(
			match[2] || '',
			decode(match[4] || ''),
			decode(match[5] || ''),
			decode(match[7] || ''),
			decode(match[9] || '')
		);
		return uri;
	}

	static file (fsPath: string): Uri {
		let pathPart = isWindows ? fsPath.replace(/\\/g, '/') : fsPath;
		let authority = '';
		if (pathPart.startsWith('//')) {
			const end = pathPart.indexOf('/', 2);
			authority = end === -1 ? pathPart.substring(2) : pathPart.substring(2, end);
			pathPart = end === -1 ? '/' : pathPart.substring(end);
		}
		if (!pathPart.startsWith('/')) {
			pathPart = `/${pathPart}`;
		}
		const uri = new Uri('file', authority, pathPart, '', '');
		return uri;
	}

	static joinPath (base: Uri, ...pathSegments: string[]): Uri {
		const joined = nodePath.posix.join(base.path || '/', ...pathSegments);
		const uri = base.with({ path: joined });
		return uri;
	}

	static from (components: { readonly scheme: string; readonly authority?: string; readonly path?: string; readonly query?: string; readonly fragment?: string }): Uri {
		const uri = new Uri(components.scheme, components.authority ?? '', components.path ?? '', components.query ?? '', components.fragment ?? '');
		return uri;
	}

	constructor (
		readonly scheme: string,
		readonly authority: string,
		readonly path: string,
		readonly query: string,
		readonly fragment: string
	) {}

	get fsPath (): string {
		let value: string;
		if (this.authority && this.path.length > 1 && this.scheme === 'file') {
			value = `//${this.authority}${this.path}`;
		} else if (/^\/[A-Za-z]:/.test(this.path)) {
			value = this.path.charAt(1).toLowerCase() + this.path.substring(2);
		} else {
			value = this.path;
		}
		return isWindows ? value.replace(/\//g, '\\') : value;
	}

	with (change: { scheme?: string; authority?: string; path?: string; query?: string; fragment?: string }): Uri {
		const uri = new Uri(
			change.scheme ?? this.scheme,
			change.authority ?? this.authority,
			change.path ?? this.path,
			change.query ?? this.query,
			change.fragment ?? this.fragment
		);
		return uri;
	}

	toString (skipEncoding?: boolean): string {
		const enc = (value: string, keep: string) => (skipEncoding ? value : encodeComponent(value, keep));
		let out = '';
		if (this.scheme) {
			out += `${this.scheme}:`;
		}
		if (this.authority || this.scheme === 'file') {
			out += `//${enc(this.authority, ':@')}`;
		}
		out += enc(this.path, '/:@');
		if (this.query) {
			out += `?${enc(this.query, '=&/:@')}`;
		}
		if (this.fragment) {
			out += `#${enc(this.fragment, '/:@')}`;
		}
		return out;
	}

	toJSON (): { scheme: string; authority: string; path: string; query: string; fragment: string; fsPath: string } {
		const json = { scheme: this.scheme, authority: this.authority, path: this.path, query: this.query, fragment: this.fragment, fsPath: this.fsPath };
		return json;
	}
}

// ------------------------------------------------------ Position / Range

export class Position implements api.Position {
	constructor (readonly line: number, readonly character: number) {
		if (line < 0 || character < 0) {
			throw new Error(`Illegal position (${line}, ${character})`);
		}
	}

	isBefore (other: Position): boolean {
		return this.compareTo(other) < 0;
	}

	isBeforeOrEqual (other: Position): boolean {
		return this.compareTo(other) <= 0;
	}

	isAfter (other: Position): boolean {
		return this.compareTo(other) > 0;
	}

	isAfterOrEqual (other: Position): boolean {
		return this.compareTo(other) >= 0;
	}

	isEqual (other: Position): boolean {
		return this.compareTo(other) === 0;
	}

	compareTo (other: Position): number {
		const delta = this.line === other.line ? this.character - other.character : this.line - other.line;
		return Math.sign(delta);
	}

	translate (lineDelta?: number, characterDelta?: number): Position;
	translate (change: { lineDelta?: number; characterDelta?: number }): Position;
	translate (lineOrChange?: number | { lineDelta?: number; characterDelta?: number }, characterDelta?: number): Position {
		const change = typeof lineOrChange === 'object' ? lineOrChange : { lineDelta: lineOrChange, characterDelta };
		const position = new Position(this.line + (change.lineDelta ?? 0), this.character + (change.characterDelta ?? 0));
		return position;
	}

	with (line?: number, character?: number): Position;
	with (change: { line?: number; character?: number }): Position;
	with (lineOrChange?: number | { line?: number; character?: number }, character?: number): Position {
		const change = typeof lineOrChange === 'object' ? lineOrChange : { line: lineOrChange, character };
		const position = new Position(change.line ?? this.line, change.character ?? this.character);
		return position;
	}
}

export class Range implements api.Range {
	readonly start: Position;
	readonly end: Position;

	constructor (start: Position, end: Position);
	constructor (startLine: number, startCharacter: number, endLine: number, endCharacter: number);
	constructor (a: Position | number, b: Position | number, c?: number, d?: number) {
		const first = typeof a === 'number' ? new Position(a, b as number) : a;
		const second = typeof a === 'number' ? new Position(c ?? 0, d ?? 0) : b as Position;
		[this.start, this.end] = first.isBeforeOrEqual(second) ? [first, second] : [second, first];
	}

	get isEmpty (): boolean {
		return this.start.isEqual(this.end);
	}

	get isSingleLine (): boolean {
		return this.start.line === this.end.line;
	}

	contains (positionOrRange: Position | Range): boolean {
		if (positionOrRange instanceof Range) {
			return this.contains(positionOrRange.start) && this.contains(positionOrRange.end);
		}
		return positionOrRange.isAfterOrEqual(this.start) && positionOrRange.isBeforeOrEqual(this.end);
	}

	isEqual (other: Range): boolean {
		return this.start.isEqual(other.start) && this.end.isEqual(other.end);
	}

	intersection (range: Range): Range | undefined {
		const start = range.start.isAfter(this.start) ? range.start : this.start;
		const end = range.end.isBefore(this.end) ? range.end : this.end;
		return start.isAfter(end) ? undefined : new Range(start, end);
	}

	union (other: Range): Range {
		const start = other.start.isBefore(this.start) ? other.start : this.start;
		const end = other.end.isAfter(this.end) ? other.end : this.end;
		return new Range(start, end);
	}

	with (start?: Position, end?: Position): Range;
	with (change: { start?: Position; end?: Position }): Range;
	with (startOrChange?: Position | { start?: Position; end?: Position }, end?: Position): Range {
		const change = startOrChange instanceof Position || startOrChange === undefined
			? { start: startOrChange, end }
			: startOrChange;
		const range = new Range(change.start ?? this.start, change.end ?? this.end);
		return range;
	}
}

export class Selection extends Range implements api.Selection {
	anchor: Position;
	active: Position;

	constructor (anchor: Position, active: Position);
	constructor (anchorLine: number, anchorCharacter: number, activeLine: number, activeCharacter: number);
	constructor (a: Position | number, b: Position | number, c?: number, d?: number) {
		const anchor = typeof a === 'number' ? new Position(a, b as number) : a;
		const active = typeof a === 'number' ? new Position(c ?? 0, d ?? 0) : b as Position;
		super(anchor, active);
		this.anchor = anchor;
		this.active = active;
	}

	get isReversed (): boolean {
		return this.anchor.isAfter(this.active);
	}
}

export class Location implements api.Location {
	uri: Uri;
	range: Range;

	constructor (uri: Uri, rangeOrPosition: Range | Position) {
		this.uri = uri;
		this.range = rangeOrPosition instanceof Position ? new Range(rangeOrPosition, rangeOrPosition) : rangeOrPosition;
	}
}

// ------------------------------------------------- Disposable / events

export class Disposable implements api.Disposable {
	static from (...disposableLikes: { dispose: () => any }[]): Disposable {
		const disposable = new Disposable(() => {
			for (const item of disposableLikes) {
				item.dispose();
			}
		});
		return disposable;
	}

	// The callback lives off-instance: an extra member (even a private
	// one) would make API-typed disposables unassignable to this class
	constructor (callOnDispose: () => any) {
		disposeCallbacks.set(this, callOnDispose);
	}

	dispose (): any {
		const call = disposeCallbacks.get(this);
		disposeCallbacks.delete(this);
		return call?.();
	}
}

const disposeCallbacks = new WeakMap<Disposable, () => any>();

export type Event<T> = api.Event<T>;

export class EventEmitter<T> implements api.EventEmitter<T> {
	private listeners: Array<{ listener: (e: T) => any; thisArgs: unknown }> = [];

	readonly event: Event<T> = (listener, thisArgs?, disposables?) => {
		const entry = { listener, thisArgs };
		this.listeners.push(entry);
		const disposable = new Disposable(() => {
			this.listeners = this.listeners.filter(item => item !== entry);
		});
		disposables?.push(disposable);
		return disposable;
	};

	fire (data: T): void {
		for (const { listener, thisArgs } of [...this.listeners]) {
			try {
				const result = listener.call(thisArgs, data);
				if (result && typeof (result as Promise<unknown>).catch === 'function') {
					(result as Promise<unknown>).catch(reportListenerError);
				}
			} catch (error) {
				reportListenerError(error);
			}
		}
	}

	dispose (): void {
		this.listeners = [];
	}
}

// VS Code isolates listener failures: one throwing listener neither
// stops the others nor reaches the firing code
const reportListenerError = (error: unknown): void => {
	const text = error instanceof Error ? error.stack ?? error.message : String(error);
	process.stderr.write(`[sidecar:error] event listener failed: ${text}\n`);
};

/** An event that never fires */
export const neverEvent: Event<any> = () => new Disposable(() => undefined);

export const neverCancelled: api.CancellationToken = {
	isCancellationRequested: false,
	onCancellationRequested: neverEvent
};

export class CancellationTokenSource implements api.CancellationTokenSource {
	private readonly emitter = new EventEmitter<any>();
	private cancelled = false;

	readonly token: api.CancellationToken = {
		isCancellationRequested: false,
		onCancellationRequested: this.emitter.event
	};

	cancel (): void {
		if (this.cancelled) {
			return;
		}
		this.cancelled = true;
		this.token.isCancellationRequested = true;
		this.emitter.fire(undefined);
	}

	dispose (): void {
		this.emitter.dispose();
	}
}

// ----------------------------------------------------------- theme / tree

export class ThemeColor implements api.ThemeColor {
	constructor (readonly id: string) {}
}

export class ThemeIcon implements api.ThemeIcon {
	static readonly File = new ThemeIcon('file');
	static readonly Folder = new ThemeIcon('folder');

	constructor (readonly id: string, readonly color?: ThemeColor) {}
}

export class TreeItem implements api.TreeItem {
	label?: string | api.TreeItemLabel;
	id?: string;
	iconPath?: string | Uri | { light: string | Uri; dark: string | Uri } | ThemeIcon;
	description?: string | boolean;
	resourceUri?: Uri;
	tooltip?: string | api.MarkdownString | undefined;
	command?: api.Command;
	collapsibleState?: api.TreeItemCollapsibleState;
	contextValue?: string;
	accessibilityInformation?: api.AccessibilityInformation;

	constructor (label: string | api.TreeItemLabel, collapsibleState?: api.TreeItemCollapsibleState);
	constructor (resourceUri: Uri, collapsibleState?: api.TreeItemCollapsibleState);
	constructor (labelOrUri: string | api.TreeItemLabel | Uri, collapsibleState?: api.TreeItemCollapsibleState) {
		if (labelOrUri instanceof Uri) {
			this.resourceUri = labelOrUri;
		} else {
			this.label = labelOrUri;
		}
		this.collapsibleState = collapsibleState;
	}
}

// ------------------------------------------------------------------ enums
//
// @types/vscode declares these as TypeScript enums, which are nominal: a
// home-grown enum would not be assignable to the API's. The runtime
// objects carry the exact member values of @types/vscode 1.74 (the
// `satisfies` pins the member names) and are typed as the API enums —
// the one sanctioned cast per enum.

export const TreeItemCollapsibleState = ({ None: 0, Collapsed: 1, Expanded: 2 } satisfies Record<keyof typeof api.TreeItemCollapsibleState, number>) as unknown as typeof api.TreeItemCollapsibleState;
export type TreeItemCollapsibleState = api.TreeItemCollapsibleState;

export const ViewColumn = ({ Active: -1, Beside: -2, One: 1, Two: 2, Three: 3, Four: 4, Five: 5, Six: 6, Seven: 7, Eight: 8, Nine: 9 } satisfies Record<keyof typeof api.ViewColumn, number>) as unknown as typeof api.ViewColumn;
export type ViewColumn = api.ViewColumn;

export const StatusBarAlignment = ({ Left: 1, Right: 2 } satisfies Record<keyof typeof api.StatusBarAlignment, number>) as unknown as typeof api.StatusBarAlignment;
export type StatusBarAlignment = api.StatusBarAlignment;

export const ProgressLocation = ({ SourceControl: 1, Window: 10, Notification: 15 } satisfies Record<keyof typeof api.ProgressLocation, number>) as unknown as typeof api.ProgressLocation;
export type ProgressLocation = api.ProgressLocation;

export const TextEditorRevealType = ({ Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 } satisfies Record<keyof typeof api.TextEditorRevealType, number>) as unknown as typeof api.TextEditorRevealType;
export type TextEditorRevealType = api.TextEditorRevealType;

// ------------------------------------------------------- wire conversion

export const toWirePosition = (position: api.Position) => ({ line: position.line, character: position.character });

export const toWireRange = (range: api.Range) => ({ start: toWirePosition(range.start), end: toWirePosition(range.end) });

export const fromWireRange = (range: { start: { line: number; character: number }; end: { line: number; character: number } }): Range =>
	new Range(range.start.line, range.start.character, range.end.line, range.end.character);
