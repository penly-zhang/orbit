# Orbit

**An Android workspace for agent conversations, project files, and code review.**

Orbit puts the workspace on your phone while your computers run the agent engines and keep the project files. Connect over SSH, continue conversations across projects and computers, inspect results, and send code review feedback from one native Android app.

Orbit is in active development. The current integration supports Codex app-server; other agent protocols are planned. Protocol-compatible forks can use the compatible-agent option, but compatibility must be verified for each fork.

## Features

- **Multiple computers, one workspace.** Keep independent SSH connections, browse projects, and switch conversations without losing drafts or reading position.
- **Agent conversations.** Stream replies and tool activity, send images and file references, queue follow-up messages, and respond to approval requests. Model, reasoning, and permission options reflect the connected engine's capabilities.
- **Project files and previews.** Browse files, read code and Markdown, view images, and preview HTML/SVG results. Open development servers through SSH tunnels.
- **Code review.** Inspect staged and unstaged changes, add file or line comments, and send feedback to a conversation. Browsing and commenting do not modify or commit files.
- **Session continuity.** Restore tabs and drafts, reconnect after interruptions, and optionally keep connections alive with an Android foreground service.
- **SSH routing.** Use direct connections, jump hosts, remote SSH aliases, or a SOCKS5 proxy. Verify and pin host keys for each connection.

## How it works

```text
Android phone                 Your computer
┌──────────────────┐   SSH    ┌──────────────────────────┐
│ Orbit            │ ──────► │ Agent engine             │
│ Conversations    │         │ Project files and Git    │
│ Files and review │         │ Development servers      │
└──────────────────┘         └──────────────────────────┘
```

The current adapter connects to an existing Codex app-server through `codex app-server proxy`. The engine runs on the computer and determines available models, tools, and permissions. Orbit does not require an OpenAI API key on the phone.

## Requirements

- **Phone:** Android 8.0 or later (API 26+).
- **Computer:** an SSH server, Git for review features, and a running, authenticated Codex app-server.
- **Connectivity:** the phone must be able to reach the computer, directly or through a configured VPN, proxy, or SSH route.
- **Development server previews:** Python 3 on the destination computer for loopback HTTP tunneling.

The current adapter targets the `app-server proxy` interface documented in this project's development baseline, Codex CLI **0.165.0**. The app-server interface is experimental; verify compatibility when upgrading the engine.

## Getting started

### 1. Build and install

Build a debug APK using the [instructions below](#build-from-source), then install it on your phone. With an authorized Android device connected:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are intended for development and testing.

### 2. Prepare your computer

Keep the computer awake, enable SSH access for your account, and make sure Codex is signed in with its shared app-server running. On macOS, SSH access is available through **System Settings → General → Sharing → Remote Login**.

Ensure `codex` is available in the remote login shell, or specify its executable path in Orbit's connection settings. Use the address, SSH port, and account for your own computer.

### 3. Add a connection

1. In Orbit, open **Settings → Add connection**.
2. Enter the computer's reachable address, SSH port, and username.
3. Enter your SSH password or import the appropriate private key on the phone.
4. Verify the displayed host fingerprint against the computer's host key before approving the first connection.
5. Enable the connection, select a project, and open or start a conversation.

To verify an Ed25519 host key on the computer, for example:

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Use the fingerprint for the key type shown by Orbit. Changed host keys are blocked until you verify and reconfigure the connection.

For advanced routes and engine adapters, see [Architecture](docs/architecture.md).

## Build from source

Install **JDK 17**, **Android SDK Platform 35**, and **Build Tools 35.0.0**. The checked-in Gradle wrapper supplies Gradle 8.11.1.

```sh
git clone https://github.com/penly-zhang/orbit.git
cd orbit
```

Set `JAVA_HOME` to your JDK and configure the Android SDK through `ANDROID_HOME` or an untracked `local.properties` file containing `sdk.dir=<your-sdk-path>`.

Build and run the standard checks:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

`sh tools/gradle.sh` runs the same tasks and can use tools installed in the ignored `.tools/` directory. It also keeps the Gradle cache local to the checkout.

### Tests

Standard tests use isolated local SSH and RPC fixtures. They cover transport, protocol handling, session isolation, file access, settings, and review workflows without a live model service.

Live probes are separate, opt-in tasks:

| Task | Purpose |
| --- | --- |
| `:core:liveSshProbe` | Read-only checks against the existing local daemon; no model inference. |
| `:core:liveSettingsProbe` | Verify settings in a disposable ephemeral thread; no model inference. |
| `:core:liveImageProbe` | Send a generated test image to a live model; performs model inference. |

Use `CODEX_POCKET_CODEX` to override the executable path for live probes. Run live probes only against an environment you intend to use for testing. Device UI checks are separate from the JVM test suite.

## Privacy and security

SSH credentials and host trust are configured on the phone. Local session metadata and text drafts use encrypted storage backed by Android Keystore; pending images are stored in app-private files excluded from backup.

Attachments you send are uploaded to the selected computer through its SSH connection. Agent access to files and tools follows the connected engine's permissions. Review permission requests before approving them.

Keep personal connection details, local paths, and operational notes in the ignored `.local/` directory. Credentials, SDK paths, build outputs, and signing material must stay out of commits. Sanitize logs and screenshots before sharing an issue.

## Current limitations

- Other agent protocols do not yet have dedicated adapters. A fork being built with Codex or its SDK does not establish compatibility.
- Official cloud relay/QR pairing, voice sessions, scheduled tasks, and cloud push delivery are not implemented.
- Background connections depend on Android process and network availability. Notifications are not guaranteed after a force-stop.
- File workflows focus on browsing, previews, references, and review; a general-purpose file editor and Git staging/commit interface are not included.
- Searches cover loaded and cached content rather than all remote history. Sharing and export include loaded messages.
- Local HTTPS development servers are not tunneled. Use an HTTP development URL for SSH preview tunneling.

## Contributing

Bug reports and focused pull requests are welcome through [GitHub Issues](https://github.com/penly-zhang/orbit/issues) and [pull requests](https://github.com/penly-zhang/orbit/pulls).

For a bug report, include the app and engine versions, reproduction steps, expected behavior, and sanitized diagnostics. For a code change, describe the resulting behavior and run the checks relevant to the affected modules.

See [Architecture](docs/architecture.md) for adapter and routing boundaries, and [AGENTS.md](AGENTS.md) for repository conventions and compatibility requirements. Preserve existing saved connections, encryption keys, sessions, and drafts when changing storage or engine integration.

## License

A project license has not yet been added to this repository.
