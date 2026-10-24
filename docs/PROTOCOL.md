# Host ↔ Sidecar protocol

The Kotlin plugin (**host**) spawns one Node process (**sidecar**) per open project. The sidecar runs the unmodified `mnemographica` VS Code extension on top of a `vscode` shim; every call the extension makes into the VS Code API that needs the IDE is forwarded here.

- Transport: JSON-RPC 2.0 over the sidecar's stdio, LSP-style framing (`Content-Length: N\r\n\r\n` + UTF-8 JSON body). Host uses `org.eclipse.lsp4j.jsonrpc` (bundled in the platform), sidecar uses `vscode-jsonrpc/node`.
- `stdout` of the sidecar is **reserved for RPC**; all human-readable sidecar logging goes to `stderr`, which the host mirrors into the "Mnemonica Sidecar" console.
- Method naming: `<area>/<verb>`. `→` request (has a response), `⇒` notification.
- Paths are plain absolute filesystem paths (`string`), never `file://` URIs, in every message. The shim converts to/from `vscode.Uri` on its side.
- Positions are zero-based `{ line, character }`, like VS Code.
- The host serializes null-valued fields explicitly (`"itemId": null`, `"context": null`, `"selection": null`). The sidecar treats `null` and an absent field the same for every optional field.
- Every message type is defined once, in `sidecar/src/protocol.ts`; the Kotlin data classes in `src/main/kotlin/.../rpc/Protocol.kt` mirror it field by field. Both sides must stay in sync; a field added on one side is added on the other in the same change.

## Lifecycle

```
host  → initialize(InitializeParams)              → InitializeResult
host  ⇒ initialized
...
host  → shutdown                                  → null      (sidecar runs deactivate(), exits 0)
```

```ts
interface InitializeParams {
  projectRoots: string[];      // content roots of the IDE project (vscode.workspace.workspaceFolders)
  extensionPath: string;       // directory that contains the extension's media/ and package.json (ExtensionContext.extensionPath)
  config: Record<string, unknown>; // flat "mnemographica.<key>" -> value. Before `initialize` the host does not know the
                                   // schema, so this holds only the values the user changed; the shim falls back to the
                                   // package.json defaults for missing keys, and the host sends a full snapshot as
                                   // `config/changed` right after `initialized`.
  ideVersion: string;          // e.g. "OpenIDE Pro 2026.2.3 (262.10968.63.1002)"
  pluginVersion: string;
  storagePath: string;         // writable per-project dir for the extension (globalStorageUri/storageUri fallback)
  theme: 'dark' | 'light';
}

interface InitializeResult {
  commands: CommandInfo[];     // from package.json contributes.commands, merged with what registerCommand actually registered
  views: ViewInfo[];           // from contributes.views + contributes.viewsContainers, in display order
  menus: MenuContribution;     // contributes.menus verbatim: view/title, view/item/context, commandPalette
  configuration: Record<string, ConfigProperty>; // contributes.configuration.properties verbatim: key "mnemographica.<name>" -> { type, default, description, enum? }
}
interface CommandInfo { command: string; title: string; category?: string; icon?: string; internal?: boolean; }
// icon: "$(refresh)" codicon id or relative path.
// internal: registered by the extension but absent from contributes.commands (e.g. treeItemClick); the host
// must not turn these into user-facing actions, they only work with arguments supplied by the extension.
interface ViewInfo    { id: string; name: string; containerId: string; containerTitle: string; }
```

The host builds the tool window, actions, context menus and the settings page from `InitializeResult`; nothing from `package.json` is hard-coded in Kotlin.

## Host → Sidecar

| Method | Kind | Params → Result | Notes |
|---|---|---|---|
| `tree/getChildren` | → | `{ viewId, itemId: string \| null }` → `TreeNode[]` | `null` = roots. Shim calls `TreeDataProvider.getChildren(element)` and `getTreeItem()` for each child. |
| `tree/getParent` | → | `{ viewId, itemId }` → `string \| null` | Only if the provider implements `getParent`; otherwise `null`. |
| `tree/selectionChanged` | ⇒ | `{ viewId, itemIds: string[] }` | Fires `TreeView.onDidChangeSelection`. |
| `tree/visibilityChanged` | ⇒ | `{ viewId, visible: boolean }` | Fires `TreeView.onDidChangeVisibility`. |
| `tree/expansionChanged` | ⇒ | `{ viewId, itemId, expanded: boolean }` | Fires `onDidExpandElement` / `onDidCollapseElement`. |
| `command/execute` | → | `{ command: string, args: unknown[], context?: { viewId, itemId } }` → `unknown` | Runs a command registered with `registerCommand`. Tree-item click commands arrive with `args` copied verbatim from `TreeNode.command.arguments` (they may contain opaque `{ "$mnemoRef": "<id>" }` tokens; pass them back untouched). Context-menu commands pass `context` instead, and the sidecar prepends the live tree element as the first argument, as VS Code does for `view/item/context`. Unknown command → JSON-RPC error `-32601`. |
| `webview/message` | ⇒ | `{ panelId, message: unknown }` | Page called `vscode.postMessage(message)`. |
| `webview/visibilityChanged` | ⇒ | `{ panelId, visible, active }` | Fires `WebviewPanel.onDidChangeViewState`. |
| `webview/disposed` | ⇒ | `{ panelId }` | User closed the tab. Fires `onDidDispose`. |
| `definition/provide` | → | `{ path, position }` → `Location[]` | Runs the registered `DefinitionProvider`. Host applies a 3 s timeout. |
| `references/provide` | → | `{ path, position, includeDeclaration: boolean }` → `Location[]` | Runs the registered `ReferenceProvider`. |
| `config/changed` | ⇒ | `{ config: Record<string, unknown> }` | Full snapshot. Shim updates its cache and fires `onDidChangeConfiguration`. |
| `workspace/foldersChanged` | ⇒ | `{ projectRoots: string[] }` | Fires `onDidChangeWorkspaceFolders`. |
| `editor/activeChanged` | ⇒ | `{ path: string \| null, selection?: Range }` | Keeps `window.activeTextEditor` current (the shim stores a lightweight snapshot; `document.getText()` reads the file from disk). |
| `uri/handle` | ⇒ | `{ uri: string }` | For `window.registerUriHandler`; the host wires it to a `mnemonica://` deep link action. Optional for the first version. |

```ts
interface TreeNode {
  id: string;                  // sidecar-assigned, stable while the element is alive
  label: string;
  description?: string;
  tooltip?: string;            // plain text or markdown
  iconId?: string;             // codicon id ("refresh", "symbol-class"); host maps to AllIcons, falls back to a generic icon
  iconPath?: string;           // absolute path to an svg/png, wins over iconId
  iconColor?: string;          // vscode.ThemeColor id ("errorForeground", "charts.red") when set on the ThemeIcon
  collapsibleState: 0 | 1 | 2; // None | Collapsed | Expanded
  contextValue?: string;       // used by `when` clauses in view/item/context
  command?: { command: string; arguments?: unknown[] };   // run on click (also on Enter)
  resourcePath?: string;       // resourceUri, if any
}
interface Location { path: string; range: Range; }
interface Range { start: Position; end: Position; }
interface Position { line: number; character: number; }
```

## Sidecar → Host

| Method | Kind | Params → Result | Host implementation |
|---|---|---|---|
| `log` | ⇒ | `{ level: 'debug'\|'info'\|'warn'\|'error', message }` | Plugin logger (`Logger.getInstance`). |
| `window/showMessage` | → | `{ type: 'info'\|'warning'\|'error', message, items: string[], modal?: boolean }` → `string \| null` | Notification balloon with action links for `items`; `modal` → `Messages.showDialog`. |
| `window/showQuickPick` | → | `{ items: { label, description?, detail? }[], placeHolder?, canPickMany?: boolean }` → `number[] \| null` | `JBPopupFactory` list popup; indexes of picked items. |
| `window/showInputBox` | → | `{ prompt?, value?, placeHolder?, password? }` → `string \| null` | `Messages.showInputDialog`. |
| `window/showOpenDialog` | → | `{ canSelectFiles, canSelectFolders, canSelectMany, title?, defaultPath?, openLabel? }` → `string[] \| null` | `FileChooser`. |
| `window/showSaveDialog` | → | `{ defaultPath?, title?, filters?: Record<string,string[]> }` → `string \| null` | `FileChooserFactory.createSaveFileDialog`. |
| `window/showTextDocument` | → | `{ path, selection?: Range, preserveFocus?: boolean, viewColumn?: number }` → `null` | `OpenFileDescriptor(...).navigate(true)` then set selection / scroll to center. |
| `window/openExternal` | ⇒ | `{ url }` | `BrowserUtil.browse`. |
| `progress/begin` / `progress/report` / `progress/end` | ⇒ | `{ id, title?, cancellable? }` / `{ id, message?, increment? }` / `{ id }` | `ProgressManager` background task in the status bar. Cancellation (optional): host ⇒ `progress/cancelled { id }`. |
| `statusBar/update` | ⇒ | `{ id, text, tooltip?, command?, visible: boolean, alignment: 'left'\|'right', priority?: number }` | One `StatusBarWidget` per `id`; `text` may contain `$(icon)` which the host strips or maps. |
| `output/append` | ⇒ | `{ channel, text }` | A console view per channel inside the "Mnemonica" tool window ("Logger" tab). |
| `output/show` | ⇒ | `{ channel, preserveFocus?: boolean }` | Activate that console tab. |
| `tree/registered` | ⇒ | `{ viewId, showCollapseAll?: boolean, canSelectMany?: boolean }` | Marks the view as live; before this, the tab shows "Loading…". |
| `tree/invalidate` | ⇒ | `{ viewId, itemId: string \| null }` | `onDidChangeTreeData` fired; `null` = whole tree. Host re-requests children for that subtree. |
| `tree/reveal` | ⇒ | `{ viewId, itemId, select: boolean, focus: boolean, expand: boolean \| number }` | Expand the path to the item (host uses `tree/getParent` to build it). |
| `tree/setMeta` | ⇒ | `{ viewId, title?, description?, message?, badge?: { value: number, tooltip } }` | Tab title / empty-state text. |
| `view/focus` | ⇒ | `{ viewId }` | Built-in `<viewId>.focus` commands end up here: activate the tool window and select the tab. |
| `webview/create` | → | `{ panelId, viewType, title, viewColumn?: number, enableScripts: boolean, retainContextWhenHidden: boolean, localResourceRoots: string[] }` → `null` | Open an editor tab (`FileEditor` backed by `JBCefBrowser`). |
| `webview/setHtml` | ⇒ | `{ panelId, html }` | Host **rewrites** the HTML before loading (see below) and serves it itself at `mnemo-res://file/.mnemo-panel/<pageId>/index.html?v=<n>`, which the browser opens with `loadURL`, so the page and its `mnemo-res://` resources share one origin (`loadHTML` would give the page a `file:///jbcefbrowser/` origin and break same-origin `fetch`). `n` increments per load so a stale `onLoadEnd` cannot release queued messages early. |
| `webview/postMessage` | ⇒ | `{ panelId, message: unknown }` | `executeJavaScript("window.__mnemoDeliver(<json>)")`; the polyfill dispatches a `MessageEvent('message', { data })` on `window`. |
| `webview/reveal` | ⇒ | `{ panelId, viewColumn?: number, preserveFocus?: boolean }` | Select the tab. |
| `webview/setTitle` | ⇒ | `{ panelId, title }` | |
| `webview/dispose` | ⇒ | `{ panelId }` | Close the tab (no `webview/disposed` echo back). |
| `commands/executeBuiltin` | → | `{ command, args: unknown[] }` → `unknown` | Commands the extension calls that VS Code itself provides. Host supports: `vscode.open` (path, optional range), `revealInExplorer` (path → Project view), `workbench.action.openSettings` (open the plugin's settings page), `workbench.view.explorer` (activate Project tool window), `setContext` (store key for `when` evaluation). Anything else → error `-32601`, which the shim turns into a rejected promise and a `warn` log. |
| `fs/watchers` | ⇒ | `{ globs: string[] }` | Informational only: the shim watches files itself (`fs.watch` / `chokidar`); the host does nothing with it in v1. |

### Webview HTML rewrite (host side)

Before loading `webview/setHtml` into JCEF, the host:

1. Injects `<meta charset="utf-8">` and then, before anything else in `<head>` (so ahead of any CSP meta), the bridge polyfill:
   ```js
   window.acquireVsCodeApi = (() => { let api; return () => api ??= {
     postMessage: m => window.__mnemoSend(JSON.stringify(m)),   // __mnemoSend is the JBCefJSQuery injection
     getState: () => window.__mnemoState, setState: s => (window.__mnemoState = s)
   }; })();
   window.__mnemoDeliver = json => window.dispatchEvent(new MessageEvent('message', { data: JSON.parse(json) }));
   ```
2. Injects a `<style>` with `:root { --vscode-...: ...; }` for every variable used by `mnemographica/media/webview.css` and the panel templates, computed from the current IDE LaF (`JBColor`, `EditorColorsManager`, `UIUtil.getLabelFont()`), plus `body.vscode-dark` / `body.vscode-light` class on `<body>`.
3. Replaces the two CDN `<script src>` URLs (`cdn.jsdelivr.net/npm/d3@7/...`, `.../three@0.160.0/...`) with the vendored copies served from the plugin when they exist (`sidecar/dist/vendor/`), leaving the CDN URL as a fallback otherwise.
4. Serves `mnemo-res://` URLs (what the shim returns from `Webview.asWebviewUri`) through a `CefSchemeHandlerFactory` that reads files under `localResourceRoots` only.

`Webview.asWebviewUri(file)` returns `mnemo-res://file/<absolute path with percent-encoded segments>` (a space becomes `%20`, which matters for `OpenIDE Pro.app`); the scheme handler must decode the path before reading. `Webview.cspSource` returns `mnemo-res:`.

## Lifecycle details and debugging

- On `shutdown` the sidecar replies `null`, runs `deactivate()`, waits for stdout to drain and exits 0. If stdin closes, it also exits 0.
- `MNEMO_SIDECAR_DEBUG=1` in the sidecar environment traces every RPC message to stderr. `MNEMO_SIDECAR_INSPECT=<port>` is reserved for the host to start Node with `--inspect=<port>`.
- The extension's strategy server binds fixed ports 9230/9231; the host should pass `MNEMOGRAPHICA_STRATEGY_PORT` / `MNEMOGRAPHICA_WS_PORT` per project once upstream supports them (see `UPSTREAM_REQUESTS.md`).

## Error handling

- A request that throws in the sidecar returns a JSON-RPC error with the stack in `data`; the host logs it and shows at most one balloon per method per session.
- If the sidecar exits, the host shows "Mnemonica sidecar stopped (exit N)" with a **Restart** link and marks all tabs as stale. It does not auto-restart in a loop.
- If no Node ≥ 18 is found, the tool window shows an explanation and a link to the settings page where the path can be set.
