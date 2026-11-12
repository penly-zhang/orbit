# Architecture

Orbit separates the Android interface from agent adapters and remote workspace services.

## Modules

- `app/`: Jetpack Compose interface, connection management, encrypted local storage, and Android background services.
- `core/`: agent contracts, Codex adapter, SSH transport, file access, Git review, and JVM integration tests.

## Agent adapters

The app depends on these contracts in `core`:

- `AgentEngine` / `AgentEngineRegistry`: discover `codex` and `traex` on the final SSH
  target and open their adapters. Registration is compiled into the app.
- `MultiAgentRepository`: aggregate host projects and ordered conversation pages; route
  each conversation, event, approval and settings request to its owning adapter.
- `AgentRepository`: projects, conversations, history, sending, settings and replies.
  Settings changes and review targets are semantic Kotlin types.
- `AgentEvent` / `TimelineUpdate`: normalized messages, turn lifecycle, usage and typed
  approval/question/MCP requests. Start collecting events before `initialize()`.
  Adapters translate the shared request presentation payloads and encode replies.
- `RemoteFiles` / `WorkspaceRepository`: files, images, previews and Git review.
  `SshWorkspace` supplies shared host services; `CodexFiles` owns native file RPC.
- `AgentTools`: file search, skills, MCP and plugins; native RPC stays in `CodexTools`.

Codex and traex use the same compatible app-server protocol adapter. Binaries are
found in the remote login PATH and standard installation locations; the connection
form contains no engine selector or executable field. For a different protocol,
implement `AgentEngine` and `AgentRepository` and add automatic discovery support.

The traex connection runs `app-server daemon start` before opening the proxy.
This reuses an existing daemon and keeps daemon startup output on stderr so the
WebSocket stream stays clean. Disconnecting Orbit closes only its proxy connection;
it never stops or restarts the shared service. Conversations owned by an embedded
server still need to be resumed through that same shared daemon on the desktop.

New conversations default to Codex when available, otherwise traex. The selected
engine is fixed at creation. Project lists combine conversations from both engines,
with independent backend cursors and a globally ordered merge. Model and tool
catalogs are requested from the conversation's engine.

Existing saved profiles retain `engineId` and `codexPath` for serialization compatibility;
production discovery no longer uses those connection-level choices. Old conversations
without engine metadata belong to Codex and retain their original IDs. traex IDs use
an adapter-boundary namespace, removed before RPC calls. Approval IDs carry the
engine and original JSON ID, preventing cross-engine replies. An unavailable engine
never causes its conversations to resume against another backend. Application ID,
vault keys, RPC client ID and existing upload/cache paths are retained.

## SSH routing

The connection editor offers three modes. They apply to both the Codex proxy and every file/Git command:

- **Direct / ProxyJump**: enter the final target and add ordered SSH jump hosts, each with its own password/key and pinned host fingerprint. A configured SOCKS5 proxy only carries the first hop; destination DNS is resolved through the preceding hop. No fallback to direct connection occurs.
- **Login auto-redirect**: enter A's address and credentials. The app starts A's interactive login shell without a PTY, allowing its existing passwordless `ssh B` startup to run. A random readiness marker separates login banners from the binary protocol. The final host must accept non-PTY shell input and run Codex. Startup scripts that require a terminal, interactive password entry or host-key confirmation need the explicit remote-alias or native jump mode instead.
- **SSH alias on A**: enter A as the connection address and B's alias (or `user@B`) as the remote target. A runs its own OpenSSH with `BatchMode=yes` and strict host-key checks, using its existing config, ProxyJump rules, identities and known_hosts. B must already be trusted and reachable without a prompt from A.

**Import SSH config** imports a portable `Host` config file from the phone, resolves a selected alias, and expands `HostName`, `User`, `Port` and `ProxyJump` into editable connection fields. Wildcards, negations, comments, quotes and inherited defaults are supported. Imported `IdentityFile` paths are hints only: select the corresponding private keys on the phone for the target and each hop. `Include`, `Match` and `ProxyCommand` are rejected with a message rather than silently bypassed; expand them first or use A's remote SSH alias mode.

## Task tracking

Host workspaces retain followed conversation subscriptions independently of whether their
reading tab is open. Saved records keep `tabOpen`, `tracked`, and turn-specific acceptance;
missing fields preserve legacy open tabs and follow records with a known turn. The app
coordinator exposes open `sessionTabs` and all retained `taskRecords` separately. Reconnect
restores both, and task readiness remains false until history has been reconciled.
Notification delivery is deduplicated by host, conversation, turn/outcome or request ID in
encrypted local storage. No phone connection or notification implies remote execution state.
