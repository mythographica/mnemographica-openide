# Upstream requests (mythographica/mnemographica)

Changes the OpenIDE port would like in the extension. None of them blocks the port: each has a local workaround in `sidecar/`, named below. The submodule stays unmodified.

## 1. Diamonds view: one TreeItem.id on two rows

`src/views/diamondsTreeProvider.ts` gives an anchor row `id = diamond:${anchor.location}`. When one `new` site anchors two types on the same type-path chain (fixtures-v2: `construction-showcase.entity.ts:216` and `:221`, under a type segment and its child segment), the same id appears twice in the tree. VS Code rejects that ("Element with id … is already registered") and the subtree fails to render.

Requested change: make the id unique per row, for example ``id = `diamond:${data.fullPath}:${anchor.location}` `` (the segment path the row sits under), or drop `id` for anchor rows.

Workaround: the sidecar's tree bookkeeping detects the reuse, logs one warning per id and gives each row its own node id.

## 2. LoggerService writes into the extension directory

`src/services/LoggerService.ts` writes `path.join(context.extensionPath, 'logs', 'server.log')`. Extension directories can be read-only (system-wide IDE plugin installs, VS Code's own extensions folder on some setups), and the file is per machine rather than per workspace.

Requested change: use `context.logUri.fsPath` (available since VS Code 1.69, inside `^1.74.0`), falling back to the extension path only when it is absent.

Workaround: none needed today. In OpenIDE the plugin directory is user-writable, so the file lands in `<plugin>/sidecar/logs/server.log`.

## 3. Topologica bootstrap needs compiled model files on disk

`src/topologica/bootstrap.ts` loads the models with `topologica(path.join(extensionPath, 'out', 'src', 'models'), define)`, which `require()`s every compiled file in that directory. A bundled build has no such directory, and a second copy of the model modules would create a second `mnemonica` / `Backend` / `Frontend` instance.

Requested change: let `loadModels` accept the already-imported model modules (or a directory override), for example `loadModels(extensionPath, modelsDir = path.join(extensionPath, 'out', 'src', 'models'))`, so bundlers can pass a directory they control.

Workaround: `sidecar/esbuild.mjs` writes one stub per model into `dist/out/src/models/`. Each stub returns the model module bundled in `main.js` through a global hook that `sidecar/src/main.ts` installs. Topologica runs unchanged.

Side observation: topologica reports `Loaded 0 root types` and logs `skipping (lowercase): instanceCreatorImpl` for every export, because self-defined mnemonica constructors are named `instanceCreatorImpl`. The models still register, because they self-define when required. The sidecar shows the same output, so this is upstream behaviour.

## 4. Fixed Strategy server ports

`extension.ts` binds 9230/9231 unless `MNEMOGRAPHICA_STRATEGY_PORT` / `MNEMOGRAPHICA_WS_PORT` are set. OpenIDE runs one sidecar per open project, so a second project gets `EADDRINUSE` (logged; trace ingestion stays off there). VS Code has the same issue with two windows.

Requested change: a setting, or falling back to an ephemeral port and publishing the chosen one (as the App Channel discovery does).

Workaround: none in the sidecar. The host may set the two environment variables per project.

## 5. `mnemographica.showProperties` is read but not contributed

`src/webview/panel.ts` reads `getConfiguration('mnemographica').get('showProperties', true)`, but `package.json` has no such property, so no settings UI (VS Code's or OpenIDE's) can change it.

Requested change: contribute it in `contributes.configuration`, or drop the read.

Workaround: none needed; the default `true` applies.
