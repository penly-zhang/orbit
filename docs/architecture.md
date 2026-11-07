# Architecture

Orbit separates the Android interface from agent adapters and remote workspace services.

## Modules

- `app/`: Jetpack Compose interface, connection management, encrypted local storage, and Android background services.
- `core/`: agent contracts, Codex adapter, SSH transport, file access, Git review, and JVM integration tests.

## Agent adapters

The connection editor's advanced settings offer **Codex** and **Codex compatible Agent**.
The compatible option requires an explicit executable path and runs that binary with
`app-server proxy`. Compatibility depends on its wire protocol and supported methods;
being built with Codex or its SDK alone is insufficient. Deepseek and OpenCode
have no dedicated adapter or verified compatibility in this repository.

The app depends on these contracts in `core`:

- `AgentEngine` / `AgentEngineRegistry`: select an adapter using the connection's stable
  `engineId`; open and close its resources. Registration is compiled into the app.
- `AgentRepository`: projects, conversations, history, sending, settings and replies.
  Settings changes and review targets are semantic Kotlin types.
- `AgentEvent` / `TimelineUpdate`: normalized messages, turn lifecycle, usage and typed
  approval/question/MCP requests. Start collecting events before `initialize()`.
  Adapters translate the shared request presentation payloads and encode replies.
- `RemoteFiles` / `WorkspaceRepository`: files, images, previews and Git review.
  `SshWorkspace` supplies shared host services; `CodexFiles` owns native file RPC.
- `AgentTools`: file search, skills, MCP and plugins; native RPC stays in `CodexTools`.

To support a compatible fork with its own name, register
`CodexEngine(EngineDescriptor("my-fork", "My Fork", requiresExecutable = true))`
in `AgentEngines.default`. For a different protocol, implement `AgentEngine` and
`AgentRepository`, translate its events and settings, then register the engine.
The engine factory owns its transport; it need not use SSH or JSON-RPC. The current
workspace contract includes the existing tools and review workflows, so a new adapter
must implement or explicitly reject unsupported operations rather than fake success.
The connection form currently stores SSH credentials and an executable path; protocols
requiring other credentials need corresponding form/storage support.

Existing saved profiles default to `engineId = "codex"`. The serialized `codexPath`
field, application ID, vault keys, RPC client ID and upload/cache paths are retained.
Changing the engine or executable clears the active workspace rather than resuming
threads against a different backend. Use separate saved connections for separate agents.
Unknown or duplicate engine IDs fail explicitly; no fallback starts an unrelated agent.

## SSH routing

The connection editor offers three modes. They apply to both the Codex proxy and every file/Git command:

- **Direct / ProxyJump**: enter the final target and add ordered SSH jump hosts, each with its own password/key and pinned host fingerprint. A configured SOCKS5 proxy only carries the first hop; destination DNS is resolved through the preceding hop. No fallback to direct connection occurs.
- **Login auto-redirect**: enter A's address and credentials. The app starts A's interactive login shell without a PTY, allowing its existing passwordless `ssh B` startup to run. A random readiness marker separates login banners from the binary protocol. The final host must accept non-PTY shell input and run Codex. Startup scripts that require a terminal, interactive password entry or host-key confirmation need the explicit remote-alias or native jump mode instead.
- **SSH alias on A**: enter A as the connection address and B's alias (or `user@B`) as the remote target. A runs its own OpenSSH with `BatchMode=yes` and strict host-key checks, using its existing config, ProxyJump rules, identities and known_hosts. B must already be trusted and reachable without a prompt from A.

**Import SSH config** imports a portable `Host` config file from the phone, resolves a selected alias, and expands `HostName`, `User`, `Port` and `ProxyJump` into editable connection fields. Wildcards, negations, comments, quotes and inherited defaults are supported. Imported `IdentityFile` paths are hints only: select the corresponding private keys on the phone for the target and each hop. `Include`, `Match` and `ProxyCommand` are rejected with a message rather than silently bypassed; expand them first or use A's remote SSH alias mode.
