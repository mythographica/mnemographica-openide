# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0]

### Added

- Kotlin host plugin for OpenIDE 2026.2 (build 262 and newer) that starts one Node.js sidecar per project and talks to it
  over JSON-RPC on stdio ([docs/PROTOCOL.md](docs/PROTOCOL.md)).
- Node sidecar that runs the unmodified mnemographica 0.5.9 VS Code extension on a `vscode` API shim.
- Mnemonica tool window with the Live Trace, Usages, Types, Flow, By Generation, Diamonds and Bagels tabs, item
  context menus, view toolbars and the extension's commands under Tools | Mnemonica.
- Webview editors on JCEF for Ψ 3D, Ψ Strategy MCP and Ψ App Channel, with IDE theme colors, a `mnemo-res://`
  resource scheme and d3 and three vendored into the plugin (no CDN access needed).
- Ctrl+Click go to definition for Mnemonica types in `.ts`, `.tsx`, `.js` and `.mjs` files, and the
  Find Mnemonica References action (editor context menu, Alt+Shift+F4).
- Settings | Tools | Mnemonica: Node.js path plus the extension's configuration options.
- Status bar widgets, notifications, progress, and a "Mnemonica Sidecar" log console.

### Known limitations

- No reference highlighting: OpenIDE Community has no TypeScript PSI.
- Two projects open at the same time conflict on the Strategy server ports 9230 and 9231.
