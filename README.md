# Codex Session Tabs

A Codex-focused JetBrains plugin for the Reworked Terminal in 2026.2 IDEs.

It is aimed at one specific workflow:

- keep the terminal tab title synchronized with Codex's session name;
- use the Codex session name directly as the tab title;
- show JetBrains' native progress animation while Codex is working;
- restore recorded Codex sessions after an IDE restart while retaining reusable launch flags.

## How it works

The plugin uses the public experimental Reworked Terminal API. It associates a terminal tab with
Codex by looking at the terminal process tree and the rollout JSONL file opened by that process.
The human title comes from `$CODEX_HOME/session_index.jsonl`. Only the session ID, title, working
directory, rollout path, and reusable CLI flags are persisted in the project's workspace state;
transcript contents, positional prompts, and one-shot inputs are never copied. Codex's transient
terminal-title updates drive the activity animation without replacing the synchronized session
name.

On startup it waits for JetBrains to restore terminal tabs. A single idle tab with the exact saved
title and working directory is reused; otherwise the plugin creates a Codex tab that directly runs
`codex <saved flags> resume <UUID>`. An existing `resume` and session ID are removed when flags are
recorded, so restoration always adds exactly one canonical resume command. Commands injected into
reused shell tabs use the unique session name and minimal quoting so shell history remains readable;
duplicate or unavailable names fall back to the exact UUID. Ambiguous tabs are left alone. Closing a
tab removes it from the restore set; the workspace state therefore represents the Codex tabs that
were open at project shutdown.

## Requirements

- A JetBrains IDE based on build 262 or newer with the Reworked Terminal
- The Codex CLI
- `lsof` on the system path

## Getting started

1. Install Codex Session Tabs from JetBrains Marketplace, or install a release ZIP with
   **Settings → Plugins → Install Plugin from Disk**.
2. In **Settings → Tools → Terminal**, select the Reworked Terminal engine.
3. Open a terminal tab and start or resume Codex normally.
4. Leave a Codex tab open when exiting the IDE to have that session restored on the next launch.

No configuration is required. The tab name and activity indicator may take a few seconds to update
after Codex starts.

## Build from source

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
