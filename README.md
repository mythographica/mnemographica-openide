# Mnemonica Graphica for OpenIDE

An OpenIDE (IntelliJ-based) plugin that runs the unmodified [mnemographica](https://github.com/mythographica/mnemographica) VS Code extension in a Node.js sidecar process. The plugin supplies a `vscode` API shim and a Kotlin host for the IDE side, so the extension's trees, 3D graph and navigation work in OpenIDE without a fork. Upstream updates arrive by moving the `mnemographica/` git submodule.

## Requirements

- OpenIDE 2026.2 (build 262 or newer).
- Node.js 18 or newer on `PATH`, or its location set in Settings | Tools | Mnemonica | Node.js path. When no path is set the plugin also looks in the newest `~/.nvm/versions/node/*/bin`, `/opt/homebrew/bin` and `/usr/local/bin`.
- A project with a `.tactica/` directory produced by [`@mnemonica/tactica`](https://www.npmjs.com/package/@mnemonica/tactica). See the upstream README for the expected files.

## Install

1. Build the zip (`./gradlew buildPlugin`, see Development) or take `build/distributions/mnemographica-openide-0.1.0.zip`.
2. In OpenIDE open Settings | Plugins, click the gear icon, choose Install Plugin from Disk and select the zip.
3. Restart the IDE and open a project that contains `.tactica/`.

## What you get

- **Mnemonica tool window** (left side) with one tab per view: Live Trace, Usages, Types, Flow, By Generation, Diamonds, Bagels. Click an item to jump to the source; right-click for the cross-navigation commands.
- **Ψ 3D**: the interactive Three.js type graph in an editor tab. d3 and three are bundled, so no network access is needed.
- **Ψ Strategy MCP** and **Ψ App Channel** tabs: spawn and watch the `@mnemonica/strategy` server, or connect to a running app's embedded WS channel (Discover & Connect).
- **Ctrl+Click** (Cmd+Click on macOS) on a Mnemonica type in `.ts`, `.tsx`, `.js` and `.mjs` files goes to its `define()` call.
- **Find Mnemonica References** in the editor context menu, bound to Alt+Shift+F4. Upstream uses Shift+F12, which is "Restore Default Layout" in the IDE default keymap; Alt+Shift+F7 is taken by "Force Step Into", and Alt+Shift+F4 is unassigned in the keymaps bundled with OpenIDE 2026.2.3 (default, macOS, Emacs, Eclipse, NetBeans, Visual Studio, Sublime).
- Every extension command under Tools | Mnemonica and in Search Everywhere; options under Settings | Tools | Mnemonica.

## Known limitations

- OpenIDE Community has no TypeScript PSI, so there is no reference highlighting on hover. Navigation works by file offset instead.
- Live Trace and the App Channel need a running app that emits dive traces (`@mnemonica/otel`, `@mnemonica/strategy`) and self-hosts the strategy channel. With the [tactica-nestjs](https://github.com/mythographica/tactica-nestjs) demo that is `npm run start:strat` (not `start:dev`: the channel is gated by `STRATEGY_CLIENT=1`), then **Ψ App Channel → Discover & Connect**, and `npm run demo:load` for traffic. A `{"available":false}` answer from `/strategy/channel` means the app runs without that variable.
- The Strategy server binds fixed ports 9230 and 9231. Two projects open at once conflict on them (the second logs `EADDRINUSE` and gets no trace ingestion) until upstream request 4 lands, see [docs/UPSTREAM_REQUESTS.md](docs/UPSTREAM_REQUESTS.md).

## Development

Prerequisites: JDK 21 or newer for Gradle (the JBR inside OpenIDE works: `export JAVA_HOME="/Applications/OpenIDE Pro.app/Contents/jbr/Contents/Home"`), Node 22 (the project uses nvm), and `git submodule update --init`.

```sh
./gradlew test buildPlugin verifyPlugin   # zip lands in build/distributions/
./gradlew runIde -PrunIdeProject=/path/to/project-with-.tactica
./gradlew runIde -PrunIdeProject=/path/to/project -PdebugRunCommand=mnemographica.showTypeGraph   # runs that command 5 s after the sidecar is up
```

`buildPlugin` and `runIde` run `npm ci && npm run build` in `sidecar/` first. Pass `-PbuildSidecar=false` to reuse an existing `sidecar/dist`.

Sidecar loop:

```sh
cd sidecar
npm run build        # bundle + copy runtime packages into dist/
npm run watch        # rebuild src/ and ../mnemographica/src on change
npm test             # builds, then runs the mock-IDE tests against the upstream fixtures
npm run typecheck
```

To use the watched bundle without re-packaging, start the IDE with `-PsidecarMain=/abs/path/to/sidecar/dist/main.js` and run Tools | Mnemonica | Restart Mnemonica Sidecar after each rebuild. `watch` does not refresh `dist/node_modules` or `dist/vendor` after the first run.

Logs:

- Sidecar stderr is mirrored into the "Mnemonica Sidecar" console tab of the tool window.
- `.intellijPlatform/sandbox/mnemographica-openide/IC-2026.2.3/log_runIde/idea.log` (the `runIde` sandbox) holds the host and the forwarded sidecar log lines.
- The extension's own log file is `<sandbox>/plugins_runIde/mnemographica-openide/sidecar/logs/server.log`.
- `MNEMO_SIDECAR_DEBUG=1` in the environment of the IDE prints every JSON-RPC message and debug lines to the sidecar's stderr. `MNEMO_SIDECAR_INSPECT=<port>` starts Node with `--inspect`.

### CI and compiling without OpenIDE

By default Gradle compiles and runs against the installed OpenIDE (`platformLocalPath` in `gradle.properties`). On a machine without it, pass `-PciPlatform=true` (or `-PplatformLocalPath=`): the build then uses IntelliJ IDEA `verifyIdeVersion` (2026.2.3) and its bundled JCEF plugin from JetBrains' repositories. `verifyPlugin` always runs against that IDEA, because the verifier cannot read the OpenIDE layout. The GitHub Actions workflow in `.github/workflows/build.yml` uses this mode.

### Bumping the submodule

```sh
git -C mnemographica fetch && git -C mnemographica checkout <tag-or-commit>
cd sidecar && npm run typecheck && npm test
```

Typecheck fails when upstream starts using a `vscode` API the shim lacks; the fix is a shim method in `sidecar/src/shim/`. Review `docs/UPSTREAM_REQUESTS.md` for requests the new version may have resolved, then commit the submodule pointer.

## Architecture

A Kotlin host (tool window, actions, settings, JCEF webviews, navigation) spawns one Node sidecar per project and talks to it over JSON-RPC on stdio. The sidecar bundles the upstream extension and a `vscode` shim that forwards every IDE-facing call to the host.

- [docs/PLAN.md](docs/PLAN.md): design, repository layout, work packages and decisions.
- [docs/PROTOCOL.md](docs/PROTOCOL.md): the host/sidecar wire contract, the only coupling between the two halves.
- [docs/UPSTREAM_REQUESTS.md](docs/UPSTREAM_REQUESTS.md): changes the port would like in mnemographica, with the local workarounds.

The `mnemographica/` submodule is read-only; the plugin never modifies it.
