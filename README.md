# Codex Session Tabs

A Codex-focused JetBrains plugin for the Reworked Terminal in 2026.2 IDEs.

It is aimed at one specific workflow:

- keep the terminal tab title synchronized with Codex's session name;
- use the Codex session name directly as the tab title;
- preserve the Codex icon/state supplied by JetBrains;
- restore recorded Codex sessions after an IDE restart with `codex resume <UUID>`.

## How it works

The plugin uses the public experimental Reworked Terminal API. It associates a terminal tab with
Codex by looking at the terminal process tree and the rollout JSONL file opened by that process.
The human title comes from `$CODEX_HOME/session_index.jsonl`. Only the session ID, title, working
directory, and rollout path are persisted in the project's workspace state; transcript contents
are never copied.

On startup it waits for JetBrains to restore terminal tabs. A single idle tab with the exact saved
title and working directory is reused; otherwise the plugin creates a Codex tab that directly runs
`codex resume <UUID>`. Ambiguous tabs are left alone. Closing a tab removes it from the restore set;
the workspace state therefore represents the Codex tabs that were open at project shutdown.

## Requirements

- A JetBrains IDE based on build 262 or newer with the Reworked Terminal
- The Codex CLI
- `lsof` on the system path

## Build and install

The default build downloads the configured GoLand SDK. To use an installed IDE instead, set
`JETBRAINS_IDE_PATH` or pass `-PlocalIdePath=/path/to/IDE`.

```shell
./gradlew buildPlugin
```

Install the ZIP from `build/distributions/` with **Settings → Plugins → Install Plugin from Disk**.

## Development

This plugin is built against the documented JetBrains terminal API and the local Codex CLI/state
format. It has no network service or external runtime dependency.

The JetBrains terminal API is explicitly experimental, so a future IDE release may require a small
compatibility update. This initial version intentionally targets build 262 rather than carrying a
Classic Terminal compatibility layer.

## License

[MIT](LICENSE)
