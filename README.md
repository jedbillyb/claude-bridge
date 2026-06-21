# ClaudeBridge

A single Paper plugin (Paper API 1.21.x) that lets server ops talk to a local,
already-authenticated **Claude Code** instance from in-game chat via `/claude`.

Every `/claude <message>` shells out to the local `claude` binary, captures and
parses its JSON output, and sends the response back into Minecraft chat. Sessions
are kept per-player (in memory) so follow-up questions retain context.

## Build

Requires nothing pre-installed except a JDK to launch Gradle — the wrapper
provisions a JDK 21 toolchain automatically.

```bash
./gradlew build
```

Output jar: `build/libs/ClaudeBridge-<version>.jar`. It bundles no dependencies
(Paper API and Gson are provided by the server at runtime).

## Install

1. Drop the jar in the server's `plugins/` folder and start once to generate
   `plugins/ClaudeBridge/config.yml`.
2. Edit `config.yml` (binary path, working directory, timeout, cooldown, etc.).
3. Deploy the `claude-mc-bridge/` folder (CLAUDE.md + rcon.py) to the
   `working-directory` configured in step 2.

### Important: this server runs under Pelican/Wings (Docker)

The Paper server — and therefore this plugin's `ProcessBuilder` — runs **inside a
Wings game container**. That has two consequences:

- The `claude` binary must be installed and authenticated **inside the container's
  persistent volume** (`/home/container`), not just on the OCI host. A binary on
  the host PATH is not visible to the plugin.
- Console commands are sent via **RCON** (enabled in `server.properties`), using
  the bundled `rcon.py`. There is no tmux/screen console. Set `RCON_PASSWORD` in
  the server's runtime environment (e.g. a Pelican startup variable); never commit
  it.

## Command

- `/claude <message>` — permission node `claudebridge.use` (default: op only).

## Configuration (`config.yml`)

| key | meaning |
|-----|---------|
| `claude-binary` | path to the authenticated `claude` binary |
| `working-directory` | `--cwd` passed to claude; must contain `CLAUDE.md` |
| `allowed-tools` | list passed to `--allowedTools` |
| `timeout-seconds` | kill claude and report a timeout after this long |
| `permission-node` | permission required to run `/claude` |
| `cooldown-seconds` | per-player rate limit between calls |
| `max-response-length` | truncate chat responses past this many chars |
| `log-file` | audit log path (relative → plugin data folder) |

## Audit log

Every invocation is appended to the audit log (`log-file`): timestamp, player,
message, full response, exit code, and duration.

## Extending (Telegram / Discord later)

All Claude logic lives in `ClaudeService` (`invoke(message, resumeSessionId)`),
which has **no Bukkit dependency**. The in-game command handler is a thin caller.
A future Telegram or Discord bot can construct a `BridgeConfig` + `ClaudeService`
and call the same method without touching the command handler.
