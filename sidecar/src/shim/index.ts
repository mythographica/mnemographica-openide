/**
 * The `vscode` module as the extension sees it (tsconfig paths and the
 * esbuild alias point `vscode` here).
 *
 * Coverage rule: exactly the members mnemographica uses
 * (`grep -rhoE "vscode\.[A-Za-z]+(\.[A-Za-z]+)?" mnemographica/src`) plus
 * what the type system demands. Interface-only API types are re-exported
 * from @types/vscode ('vscode-api'); every value is implemented here.
 *
 * Conformance: each namespace object is declared as
 * `Pick<typeof api.<namespace>, <used members>>`, so its members are
 * checked against the real signatures (overloads included) and the
 * extension compiles against the real types. A full
 * `typeof import('vscode')` would require implementing the ~1000 members
 * the extension never touches; the Pick keeps the check strict for what
 * is used and silent for the rest. Value classes `implements` their API
 * declaration (see types.ts).
 */
import type * as api from 'vscode-api';

export { commands } from './commands';
export { env } from './env';
export { languages } from './languages';
export {
	CancellationTokenSource,
	Disposable,
	EventEmitter,
	Location,
	Position,
	ProgressLocation,
	Range,
	Selection,
	StatusBarAlignment,
	TextEditorRevealType,
	ThemeColor,
	ThemeIcon,
	TreeItem,
	TreeItemCollapsibleState,
	Uri,
	ViewColumn
} from './types';
export { window } from './window';
export { workspace } from './workspace';

export type CancellationToken = api.CancellationToken;
export type Definition = api.Definition;
export type DefinitionProvider = api.DefinitionProvider;
export type Event<T> = api.Event<T>;
export type ExtensionContext = api.ExtensionContext;
export type LocationLink = api.LocationLink;
export type OutputChannel = api.OutputChannel;
export type ProviderResult<T> = api.ProviderResult<T>;
export type ReferenceContext = api.ReferenceContext;
export type ReferenceProvider = api.ReferenceProvider;
export type StatusBarItem = api.StatusBarItem;
export type TextDocument = api.TextDocument;
export type TreeDataProvider<T> = api.TreeDataProvider<T>;
export type TreeView<T> = api.TreeView<T>;
export type WebviewPanel = api.WebviewPanel;
