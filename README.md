# ACP Agent Client for JetBrains IDEs

A JetBrains (IntelliJ Platform) plugin that is a **generic [ACP](https://agentclientprotocol.com)
(Agent Client Protocol, JSON-RPC 2.0) client for a remote ACP agent reachable over a WebSocket**.

- You chat in the **ACP Agent** tool window. The remote agent owns the model and the conversation.
- **Every command and file operation the agent requests runs on your machine, in the open project.**
  Commands are local processes started in the project root. File reads return unsaved editor text.
  File writes go through the IDE's document API, so you can undo them.
- Nothing in it is specific to one agent. The endpoint URL and the auth method are settings.
  [agent-swarm](https://github.com/vasyaod/agent-swarm) is just the first agent it was used with.

It works in IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, CLion and the other IntelliJ-based IDEs,
2024.2 and newer. It depends only on `com.intellij.modules.platform` and does not need a
JetBrains AI subscription.

## Why a plugin, and not JetBrains AI Assistant's built-in ACP support

JetBrains AI Assistant can host ACP agents natively. You register them in `~/.jetbrains/acp.json`
(`{"agent_servers": {"Name": {"command": …, "args": […], "env": {…}}}}`), but it runs them only as
**local stdio processes**. A remote agent behind `wss://…` cannot be registered there directly.
You would need a local stdio⇄WebSocket relay, plus an AI Assistant setup. This plugin talks to the
remote WebSocket agent directly, with its own tool window, and serves the agent's terminal, file
and permission requests itself.

## Install

1. Download `jetbrains-acp-plugin-<version>.zip` from the
   [latest release](https://github.com/vasyaod/jetbrains-acp-plugin/releases/latest).
   Do not unzip it.
2. In the IDE, go to **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip,
   and restart if asked.
3. Open **View → Tool Windows → ACP Agent** (right side bar).

This works the same on macOS, Linux and Windows. On Windows the agent's commands must suit the
local shell; agents usually send `/bin/sh -c …`.

## Configure

Go to **Settings → Tools → ACP Agent**:

| Setting | Meaning |
|---|---|
| Agent WebSocket URL | `ws://` or `wss://` URL of the ACP agent |
| Authentication | `Token`, `Sign in with browser`, or `None` |
| Bearer token | Token mode only. Stored in the IDE password safe (OS keychain / KeePass), per URL, never in the plain settings file. It is sent as `Authorization: Bearer <token>` on the WebSocket upgrade. The field stays empty after saving; paste a new token to replace the stored one. |
| Sign In with Browser… | OAuth 2.0 authorization code + PKCE (S256) as a public client, with a loopback redirect to `http://127.0.0.1:<random port>/callback`. The resulting access token goes to the password safe. |
| Authorization server (issuer) | Optional fallback for browser sign-in (see below) |
| Client ID / Scope | Browser sign-in. The client ID defaults to `jetbrains-acp-plugin`. |
| Extra headers | `Name: value` per line, added to the WebSocket upgrade |
| Allow every command and edit without asking | Auto-answers permission prompts with "Allow" |
| Show the agent's thinking | Renders `agent_thought_chunk`s |

**How browser sign-in finds the authorization server.** It reads RFC 9728
`/.well-known/oauth-protected-resource` on the endpoint's origin (first the path-specific
document, then the root one). If that is missing, it uses the configured issuer. It then reads the
issuer's RFC 8414 `/.well-known/oauth-authorization-server` metadata, falling back to OpenID
`/.well-known/openid-configuration`.

### Example: agent-swarm

- URL: `wss://agent-swarm.f-proj.com/acp`
- Authentication: `Token`, with the swarm's **operator** token. The swarm refuses agent-run
  tokens for ACP by design.
- Browser sign-in: the swarm's auth server is `https://auth.f-proj.com` (put it in the issuer
  field). Note that `/acp` does not accept those tokens yet; that is a server-side change.

### Any other ACP agent

Point the URL at any agent that speaks ACP over a WebSocket (text frames, one JSON-RPC message per
frame), and pick the auth mode it needs. The plugin only uses standard ACP:

- `initialize`, advertising `fs.readTextFile`, `fs.writeTextFile` and `terminal`
- `session/new` with the project root as `cwd`
- `session/prompt` and the `session/cancel` notification
- `session/update` (`agent_message_chunk`, `agent_thought_chunk`, `tool_call`, `tool_call_update`, `plan`)
- the agent→client requests `fs/read_text_file`, `fs/write_text_file`, `terminal/create`,
  `terminal/output`, `terminal/wait_for_exit`, `terminal/kill`, `terminal/release` and
  `session/request_permission`

An agent that runs as a local stdio process does not need this plugin. Use JetBrains AI
Assistant's `acp.json` for those, or put a stdio⇄WebSocket bridge in front of it.

## Using it

- Type a message and press Enter (Shift+Enter adds a new line). The reply streams in.
- Each tool call is a card with its kind, title and status. For commands, the card shows the live
  local output and the directory it ran in. File reads and writes are listed too.
- Before a command or an edit, the agent asks for permission. You get a dialog with **Allow**,
  **Always allow "<kind>" in this chat**, and **Reject**. "Always" is remembered per tool kind until
  you start a new chat.
- **Stop** sends `session/cancel`. The turn ends with `cancelled`, and a running local command is
  killed when the agent asks for it (and always when the connection ends).
- **New chat** starts a new `session/new`. Inside one chat, every prompt reuses the same sessionId.
- If the connection drops, you see an error. The next message reconnects and starts a new
  session, because the agent cannot resume the old one (`session/load` is not used).

### Errors you may see

| Situation | Message |
|---|---|
| Close code 4401, or HTTP 401 at the handshake | "The agent rejected the credentials…", with a **Sign in again** / **Open settings** notification |
| Close code 4403 | "The agent refused this credential (4403)…": the token is valid but not allowed |
| HTTP 403 at the handshake | "The agent refused the connection (HTTP 403)…". Servers that close before accepting the WebSocket (as agent-swarm does) show both 4401 and 4403 this way. |
| DNS failure, refused connection, timeout, TLS failure | "Cannot reach host:port…" / "Cannot resolve host…" |
| Connection lost mid-turn | "The connection to the agent dropped…", then a new session on the next message |
| JSON-RPC error from `session/prompt` | "The agent reported an error (code): …" |

## Architecture

```
core/   plain Kotlin, no IntelliJ classes, unit-tested with JUnit
  Json            tiny JSON reader/writer (no third-party dependency)
  Transport       WebSocket on java.net.http, with close-code and handshake-status classification
  JsonRpc         symmetric JSON-RPC 2.0 peer (our requests + serving the agent's)
  AcpClient       initialize / session/new / session/prompt / session/cancel, dispatch of agent requests
  FileSystem      FileSystemHandler interface, plus line/limit slicing
  Terminals       ProcessStarter interface and TerminalManager (outputByteLimit, truncation, exit status, kill)
  Permissions     PermissionPrompter interface, plus auto-allow and per-chat "always" per kind
  oauth/OAuth     RFC 9728 / RFC 8414 discovery, PKCE S256, loopback receiver, token exchange
src/main/kotlin/…/ide   the IntelliJ layer
  ChatView / ChatController   tool window UI, connection and session lifecycle
  IdeFileSystem         FileDocumentManager reads, WriteCommandAction writes (undoable)
  IdeProcessStarter     GeneralCommandLine + KillableProcessHandler in the project root
  IdePermissionPrompter native Allow / Always / Reject dialog
  AcpSettings / AcpConfigurable / TokenStore   settings page, with PasswordSafe for secrets
  AcpSignIn             browser sign-in task (BrowserUtil)
```

The agent's requests are served on a background pool. Permission dialogs and document writes hop
to the EDT with `invokeAndWait`. UI updates use `invokeLater`.

## Build and test

You need JDK 21. Gradle comes from the wrapper.

```bash
./gradlew test          # core tests: mock ACP agent over WebSocket, mock OAuth server
./gradlew buildPlugin   # build/distributions/jetbrains-acp-plugin-<version>.zip
./gradlew runIde        # a sandbox IDE with the plugin installed
./gradlew verifyPlugin  # JetBrains Plugin Verifier vs IDEA 2024.2, 2025.2 and 2026.3 (downloads ~3 GB)
```

The tests run headless; no IDE window is opened:

- `AcpClientTest` runs a **mock ACP agent over WebSocket** that checks the `Authorization` header,
  then drives a scripted turn: a text chunk, `tool_call`, a permission request, `terminal/create`
  of `hostname && pwd`, `wait_for_exit`, `output`, `release`, `fs/read_text_file` (with
  line/limit), `fs/write_text_file`, then `end_turn`. It asserts that the command really ran on
  this machine (same `hostname`) in the test project directory, and that the file landed on disk.
  It also covers multi-turn on one sessionId, "always allow", reject, cancel (a `sleep 30` killed
  within seconds), 4401, 4403, HTTP 401/403/404 at the handshake, unreachable hosts, a drop
  mid-turn followed by a reconnect, and JSON-RPC errors.
- `OAuthTest` runs a **mock authorization server** (RFC 9728 + RFC 8414 metadata, `/authorize`,
  and `/token` checking the S256 verifier). It covers the full browser flow over the loopback
  redirect, the issuer fallback, a denied authorization, and using the token on the WebSocket.
- `IdeHandlersTest` (IntelliJ test framework, headless) covers the IDE side: a read returns
  the unsaved Document text, a write goes through the Document, is saved to disk and can be
  undone, missing directories are created, and a process started through `GeneralCommandLine`
  runs in the given directory with the given environment and can be killed.
- `RealServerSmokeTest` connects to `wss://agent-swarm.f-proj.com/acp` when
  `$AGENT_SWARM_RUN_TOKEN` is set. It expects a clean access error, since run tokens are refused,
  and never prints the token. Without the variable it is skipped (as in CI).

CI (`.github/workflows/build.yml`) runs the tests and `buildPlugin` on every push. On a `v*` tag it
attaches the plugin zip to a GitHub Release.

## Manual test checklist (owner)

1. Install the release zip (Settings → Plugins → Install Plugin from Disk…) into IntelliJ IDEA
   2024.2 or newer, and restart.
2. Settings → Tools → ACP Agent. Set the URL to `wss://agent-swarm.f-proj.com/acp`, choose
   Authentication `Token`, paste the operator token, and press OK. Reopen the page: the field is
   empty and the page says "A token is stored in the IDE password safe".
3. Open a project with a `README.md`, then View → Tool Windows → ACP Agent.
4. Send: `Run hostname and pwd, then read README.md and summarise it.`
   - A permission dialog appears for `hostname && pwd`. Click **Allow**.
   - The command card shows `$ hostname && pwd (running locally in <project path>)`, then your
     machine's hostname and the project path, then `[exit 0]`.
   - A read card for README.md appears, and the reply summarises your local README.
5. Multi-turn: send `What directory were we in?`. It answers from the same session; there is no
   new "New session" line.
6. Edit and undo: send `Create notes/hello.txt containing hi`. Allow the edit. The file appears in
   the project. Open it, press Undo (⌘Z / Ctrl+Z), and the change is reverted.
7. Unsaved buffer: change README.md in the editor **without saving**, then ask
   `Read README.md and quote its first line`. The answer shows your unsaved text.
8. Stop: send `Run sleep 120, then say done`, allow it, and press **Stop** within a few seconds.
   "Stopped." appears, and the card shows `[killed: SIGKILL]` (or the agent reports the cancel).
9. New chat: press **New chat** and send a message. A new "New session" line appears, and an
   "Always allow" given earlier asks again.
10. Bad token: store a wrong token and send a message. You get a red error line and a
    notification with **Open settings** (agent-swarm answers HTTP 403 at the handshake).
11. Drop: disconnect the network mid-turn (or restart the agent). You get an error line, and the
    next message reconnects with a new session.
12. Optional: Authentication `Sign in with browser`, with issuer `https://auth.f-proj.com`. Click
    **Sign In with Browser…** and finish in the browser. The page says "Signed in". agent-swarm does
    not accept these tokens on `/acp` yet, so expect the access error from step 10 when chatting.

## License

MIT
