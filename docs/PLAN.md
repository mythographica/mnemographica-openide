# Mnemographica for OpenIDE: port plan

Goal: ship the [mnemographica](https://github.com/mythographica/mnemographica) VS Code extension as an OpenIDE plugin **without forking its code**. The extension keeps running in Node, unchanged; the IDE side is a thin Kotlin host. Upstream updates are picked up by moving the `mnemographica/` submodule.

## Why this shape

The extension is ~10k lines of TypeScript plus 7.6k lines of browser JS (Three.js scene). Only ~60 distinct VS Code API entry points are used, concentrated in `views/`, `commands/`, `webview/`, `providers/` and `extension.ts`. The data core (`core/`, `models/`, `graph/`, `strategy/`) does not import `vscode` at all. Reimplementing those 60 entry points as a `vscode` shim that talks JSON-RPC to the IDE is far less work than rewriting the views in Kotlin, and it keeps one source of truth.

Cost of the approach: the user needs Node ≥ 18 on the machine. Mnemonica users already have it.

## Target environment (verified 2026-10-25)

| Item | Value |
|---|---|
| IDE | OpenIDE Pro 2026.2.3, build `262.10968.63.1002`, product code `IC` (IntelliJ Community base), installed at `/Applications/OpenIDE Pro.app` |
| JCEF | present (`jcef-plugin`, JBR `25.0.4+1-508.27-jcef`) |
| JavaScript/TypeScript plugin | **absent** (Community). `.ts` files are TextMate + `lsp4ij`; there is no TypeScript PSI |
| Bundled libs usable by the plugin | `eclipse.lsp4j.jsonrpc.jar` (JSON-RPC over stdio) |
| Java for Gradle | no system JDK; use the IDE's JBR: `JAVA_HOME="/Applications/OpenIDE Pro.app/Contents/jbr/Contents/Home"` (Java 25) |
| Gradle | not installed; use the wrapper (Gradle 9.8.2 is current) |
| IntelliJ Platform Gradle Plugin | 2.19.0 is current on the Plugin Portal |
| Node | v22.11.0 via nvm (`~/.nvm`); `vscode-jsonrpc` 10.0.0 on npm |
| Network | Gradle Plugin Portal, Maven Central, npm reachable |

## Architecture

```
┌──────────────── OpenIDE (Kotlin plugin) ─────────────────┐     ┌───────── Node sidecar ──────────┐
│ SidecarService (project)  spawn node + JSON-RPC (lsp4j)  │◄───►│ main.ts: rpc connection          │
│ MnemonicaToolWindow: one tab per view, generic JSON tree │     │ vscode shim (require('vscode'))  │
│ DynamicActions: commands from InitializeResult           │     │   window/commands/workspace/     │
│ WebviewEditor: JBCefBrowser + mnemo-res:// scheme        │     │   languages/env/Uri/Position…    │
│ GotoDeclarationHandler + "Find Mnemonica References"     │     │ mnemographica/src/extension.ts   │
│ Settings page from contributes.configuration             │     │   (unchanged, via submodule)     │
│ Status bar widgets, notifications, Logger console        │     │ strategy server, ws, fs.watch    │
└──────────────────────────────────────────────────────────┘     └──────────────────────────────────┘
```

The wire contract is [PROTOCOL.md](PROTOCOL.md). It is the only coupling between the two halves; each half is developed and tested against it independently.

## Repository layout

```
mnemographica-openide/
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*   # Kotlin plugin
├── src/main/kotlin/ru/mythographica/mnemographica/
│   ├── sidecar/      NodeLocator, SidecarProcess, SidecarService (per project), RpcClient
│   ├── rpc/          Protocol.kt (data classes mirroring sidecar/src/protocol.ts), HostEndpoint (sidecar→host handlers)
│   ├── toolwindow/   MnemonicaToolWindowFactory, ViewTab, JsonTreeModel, TreeNodeRenderer, IconMapper, WhenClause
│   ├── actions/      DynamicCommandActions, ViewTitleActionGroup, ItemContextActionGroup
│   ├── webview/      WebviewPanelRegistry, WebviewFileEditor, WebviewVirtualFile, ResourceSchemeHandler, HtmlRewriter, ThemeCss
│   ├── navigation/   MnemonicaGotoDeclarationHandler, FindReferencesAction
│   ├── settings/     MnemonicaSettings (PersistentStateComponent), MnemonicaConfigurable
│   └── ui/           StatusBarWidgets, Notifications, LoggerConsole
├── src/main/resources/META-INF/plugin.xml, icons/
├── sidecar/
│   ├── package.json, tsconfig.json, esbuild.mjs
│   ├── src/main.ts            entry: connect RPC, build ExtensionContext, call activate()
│   ├── src/protocol.ts        message types (source of truth for PROTOCOL.md)
│   ├── src/shim/              the `vscode` module: index.ts re-exports; types.ts (Uri, Position, Range, Location, Selection, Disposable, EventEmitter, ThemeIcon, ThemeColor, TreeItem, …), window.ts, commands.ts, workspace.ts, languages.ts, env.ts, webview.ts, tree.ts
│   ├── test/                  mock-IDE harness + fixture-driven tests
│   └── dist/                  build output: main.js, media/, package.json, vendor/ (gitignored)
├── mnemographica/             git submodule, read-only
└── docs/                      this plan, PROTOCOL.md
```

Build integration: Gradle task `buildSidecar` runs `npm ci && npm run build` in `sidecar/` and `prepareSandbox` copies `sidecar/dist/` into the plugin as `<plugin>/sidecar/`. At runtime `extensionPath` = `<plugin>/sidecar`, which contains `package.json` (copied from `mnemographica/package.json`) and `media/`.

## Work packages

Each package has an owner (a subagent), a folder it may write to, and a verification that must pass before it reports done. Nobody commits; the orchestrator reviews and commits.

### WP1: Kotlin host (owner: agent `host`, model: opus). Writes `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew*`, `src/`, `.run/`.

1. Gradle project on IntelliJ Platform Gradle Plugin 2.x, Kotlin JVM. `intellijPlatform { local("/Applications/OpenIDE Pro.app/Contents") }` for development so `runIde` starts OpenIDE itself; a commented `intellijIdeaCommunity("2026.2.x")` alternative for CI. `bundledPlugins` none; declare dependency on `com.intellij.modules.platform` only. Plugin id `ru.mythographica.mnemographica`, since-build `262`.
2. `sidecar/` discovery: settings value → `PATH` → `~/.nvm/versions/node/*/bin/node` (newest) → `/opt/homebrew/bin/node`, `/usr/local/bin/node`. Require ≥ 18. Spawn with cwd = extensionPath, `stdout` for RPC, `stderr` to the Logger console.
3. JSON-RPC via bundled lsp4j: `Launcher.Builder` with a `HostEndpoint` interface annotated `@JsonRequest/@JsonNotification` for every sidecar→host method in PROTOCOL.md, and a `SidecarEndpoint` proxy for host→sidecar methods. Data classes in `rpc/Protocol.kt`.
4. Tool window "Mnemonica" (icon from `mnemographica/media/psi-icon.svg`), one tab per `InitializeResult.views` entry, each a `Tree` over an async `JsonTreeModel` that calls `tree/getChildren` lazily, re-fetches on `tree/invalidate`, handles `tree/reveal`, `tree/setMeta`, sends selection/expansion events. Click or Enter runs `TreeNode.command` through `command/execute`. Right-click shows `view/item/context` items whose `when` matches; the tab toolbar shows `view/title` items. `WhenClause` supports `view == id`, `viewItem == x`, `viewItem =~ /regex/`, `!`, `&&`, `||`, parentheses, and `setContext` keys.
5. Actions: every `CommandInfo` becomes an `AnAction` registered at runtime (`ActionManager.registerAction`) in group `Mnemonica` under Tools and findable in Search Everywhere; icons via `IconMapper` (codicon id → `AllIcons.*`, fallback generic).
6. Navigation: `GotoDeclarationHandler` for files with `.ts/.tsx/.js/.mjs` extensions that calls `definition/provide` (3 s timeout, under a non-blocking read action / in a background task when needed) and returns `FakePsiElement`s that navigate with `OpenFileDescriptor`. "Find Mnemonica References" action (Shift+F12 in editor context menu) calling `references/provide` and showing results in a `UsageView`-style popup or the Find tool window.
7. Settings: `PersistentStateComponent` at project level with keys from `InitializeResult.configuration` (plus `nodePath`), a `Configurable` page generated from the schema (boolean → checkbox, number → spinner, string → text field, enum → combo). On change push `config/changed`.
8. Notifications, quick pick, input box, open/save dialogs, progress, status bar widgets, `output/*` console tabs, `window/openExternal`, `commands/executeBuiltin` as specified.
9. Sidecar lifecycle: start on project open (lazy on first tool window / action use is acceptable), `shutdown` on project close, crash balloon with Restart.

**Done when** `./gradlew buildPlugin verifyPlugin` passes with the JBR, and `./gradlew runIde` opens OpenIDE with a "Mnemonica" tool window that shows the trees from a sidecar (use the sidecar from WP2 if it exists, otherwise the host must include `src/test/resources/fake-sidecar.js`, a 100-line Node script that speaks the protocol with canned trees, so the host can be exercised alone). Unit tests for `WhenClause` and `IconMapper`.

### WP2: Node sidecar with `vscode` shim (owner: agent `sidecar`, model: opus). Writes `sidecar/` only.

1. `sidecar/package.json`: deps `vscode-jsonrpc`, `chokidar`; devDeps `typescript`, `esbuild`, `@types/node`, `@types/vscode@1.74`. Node ≥ 18 engines. Scripts: `build` (esbuild bundle of `src/main.ts` to `dist/main.js`, platform node, cjs, `alias: { vscode: './src/shim/index.ts' }`, externals none; then copy `../mnemographica/media/` and `../mnemographica/package.json` into `dist/`), `typecheck`, `test`.
2. The bundle includes `mnemographica/src/extension.ts` (import it from `../mnemographica/src/extension`), which pulls `~tactica/*` via its tsconfig paths — configure esbuild/tsconfig paths so `~tactica/*` resolves to `../mnemographica/.tactica/*` (the generated types are committed in the submodule).
3. Shim surface = exactly what `grep -rhoE "vscode\.[A-Za-z]+(\.[A-Za-z]+)?" mnemographica/src` lists, plus whatever `tsc` demands. Implement value types faithfully (`Uri.file/parse/joinPath/fsPath/toString`, `Position/Range/Selection/Location/LocationLink` with `isBefore/contains/with` as used, `Disposable.from`, `EventEmitter`, `ThemeIcon/ThemeColor`, `TreeItem`, `TreeItemCollapsibleState`, `ViewColumn`, `StatusBarAlignment`, `ProgressLocation`, `TextEditorRevealType`, `CancellationTokenSource`).
4. `tree.ts`: `createTreeView(viewId, { treeDataProvider, ... })` keeps `Map<id, element>` per view, assigns ids (`<viewId>:<counter>`), clears subtree ids on invalidate, implements `TreeView` (`reveal`, `title`, `description`, `message`, `badge`, `selection`, `visible`, `onDidChangeSelection`, `onDidChangeVisibility`, `onDidExpandElement`, `onDidCollapseElement`, `dispose`). `getTreeItem` results (label possibly `{label, highlights}`, tooltip possibly `MarkdownString`, iconPath possibly `ThemeIcon | Uri | {light,dark}`) are normalised into `TreeNode`.
5. `commands.ts`: registry; `executeCommand` runs local commands first, then `<viewId>.focus` → `view/focus`, then `commands/executeBuiltin`.
6. `workspace.ts`: `workspaceFolders`, `getWorkspaceFolder`, `getConfiguration(section)` returning an object with `get/has/update/inspect` over the cached snapshot (`update` → host `config/update` is not in v1; log a warning and update the cache), `onDidChangeConfiguration`, `createFileSystemWatcher(glob)` with chokidar (base = each workspace folder), `findFiles(include, exclude, max)` with a small glob walker (`fast-glob` is allowed), `openTextDocument(path)` returning a `TextDocument` snapshot read from disk (`getText`, `lineAt`, `positionAt`, `offsetAt`, `uri`, `fileName`, `languageId` by extension).
7. `window.ts`: all `show*` → RPC; `createWebviewPanel` → `webview.ts`; `createStatusBarItem`, `createOutputChannel`, `withProgress`, `activeTextEditor` snapshot, `registerUriHandler`, `showTextDocument`.
8. `languages.ts`: store a single definition and reference provider; handle `definition/provide` / `references/provide` by building a `TextDocument` from disk and calling the provider with a never-cancelled token; normalise `Definition | LocationLink[]` into `Location[]`.
9. `env.ts`: `openExternal`, `appName`, `uriScheme`.
10. `main.ts`: create the connection on `process.stdin/stdout`, redirect `console.*` to `stderr`, handle `initialize` (build `ExtensionContext` with `subscriptions`, `extensionPath/Uri`, `globalState/workspaceState` backed by a JSON file in `storagePath`, `asAbsolutePath`), call `activate`, return `InitializeResult` assembled from `dist/package.json` contributes + the actual command registry; `shutdown` → `deactivate` + dispose subscriptions + exit.
11. Tests (`sidecar/test/`, plain `node --test`): a mock host that spawns `dist/main.js`, sends `initialize` with `projectRoots = [mnemographica/test/fixtures-v2]` (and the other fixture dirs), then walks every view with `tree/getChildren` to depth 3 and asserts non-empty roots for the views that have data in that fixture, executes a tree-item command and asserts the resulting `window/showTextDocument` (or equivalent) request, calls `definition/provide` at a known `define()` site, opens `mnemographica.showTypeGraph` and asserts a `webview/create` + `webview/setHtml` pair containing `acquireVsCodeApi`.

**Done when** `npm run typecheck`, `npm run build` and `npm test` pass under nvm Node 22 and the test prints the root labels of all seven views for `fixtures-v2`.

### WP3: Webview on JCEF (owner: agent `webview`, model: opus; starts after WP1 and WP2 report). Writes `src/main/kotlin/.../webview/`, `sidecar/scripts/vendor.mjs`, `sidecar/dist/vendor` via build, and the minimal registration lines in `plugin.xml`/`HostEndpoint` that WP1 left as TODO hooks.

1. `WebviewFileEditor` (a `FileEditorProvider` over a light `WebviewVirtualFile`) hosting `JBCefBrowser`; one `JBCefJSQuery` per panel bound to `window.__mnemoSend`; `webview/postMessage` → `executeJavaScript`; `onDidChangeViewState` from editor selection events; closing the tab → `webview/disposed`.
2. `ResourceSchemeHandler` for `mnemo-res://` restricted to `localResourceRoots`; `HtmlRewriter` + `ThemeCss` as in PROTOCOL.md; CSS variable list extracted from `mnemographica/media/webview.css` and `src/webview/*.ts` templates with a mapping table to IDE colors (editor background/foreground, panel, sideBar, button, input, list, focusBorder, font family/size, charts.*).
3. Vendor `d3@7` and `three@0.160.0` into `sidecar/dist/vendor/` at build time (download in `vendor.mjs`, pinned versions and sha256), and rewrite the CDN URLs.
4. Live Trace round trip: `Ψ App Channel` tab → Discover & Connect against the `tactica-nestjs` demo (`git clone https://github.com/mythographica/tactica-nestjs` into a scratch dir, `npm install`, `npm run start:dev`, `npm run demo:load`).

**Done when** `runIde` shows `Ψ 3D` rendering the fixture graph with IDE theme colors, Save layout works (save dialog → file written), and the App Channel tab connects to the demo app and Live Trace fills.

### WP4: packaging and polish (owner: agent `polish`, model: sonnet; after WP3). Writes `README.md`, `CHANGELOG.md`, `plugin.xml` description, `.github/workflows/`, settings page texts.

1. Plugin description, vendor, change notes; README with install steps (Node requirement, where to point `nodePath`), development loop (`runIde`, `npm run build --watch`), how to bump the submodule.
2. GitHub Actions: `buildPlugin` + `verifyPlugin` + sidecar tests on `ubuntu-latest` with `intellijIdeaCommunity("2026.2.x")` since the OpenIDE app is not available in CI.
3. `verifyPlugin` clean against the local OpenIDE; no deprecated API warnings left that are trivially fixable.

## Conventions for all agents

- Shell preamble for anything touching Node: `export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use 22`. For Gradle: `export JAVA_HOME="/Applications/OpenIDE Pro.app/Contents/jbr/Contents/Home"`.
- `mnemographica/` is read-only. If something there blocks you, write the exact upstream change you need into `docs/UPSTREAM_REQUESTS.md` and work around it locally.
- Stay inside your folders. Do not run `git commit`, `git add`, `git stash` or switch branches. Do not edit the other agent's folders; if you need something from them (a hook, a field), describe it in your report.
- Kotlin: official code style, 4 spaces; `ktlint`-clean is not required but keep imports sorted and no wildcard imports. TypeScript: strict, ESLint config copied from `mnemographica/eslint.config.js` with the `vscode`-specific bits removed; tabs, as upstream.
- Protocol changes: edit `sidecar/src/protocol.ts` **and** `docs/PROTOCOL.md` **and** `rpc/Protocol.kt` together, or, when you do not own one of them, write the needed change into your report instead of guessing.
- Verification is part of done: paste the commands you ran and their last lines into your report. Nothing is done because it compiles.

## Known risks and the decision taken

| Risk | Decision |
|---|---|
| No TypeScript PSI in OpenIDE Community | Go-to-definition via `GotoDeclarationHandler` by offset; references via a dedicated action. No reference highlighting on hover; accepted. |
| User has no Node | Clear message + settings field. A self-contained binary (Node SEA) is a later option, not in v1. |
| Two processes to debug | Sidecar accepts `MNEMO_SIDECAR_INSPECT=port` to start with `--inspect`; every RPC message is logged at debug level on both sides. |
| `@types/vscode` drift when upstream starts using a new API | The sidecar typecheck fails loudly; the fix is one shim method. |
| CDN scripts blocked | Vendored d3/three in WP3. |
| Russian marketplace packaging rules | Unknown; WP4 checks `marketplace.openide.ru` publishing docs before building the zip. |
