# Orbit

Orbit is a mobile-first Android workspace for agent engines. The phone is the primary work surface; computers host the execution environment. The current implementation integrates Codex app-server over SSH. Keep the product name engine-neutral and describe additional engines as planned until implemented.

## Workspace

- Run commands from the repository root; checkout paths are machine-specific.
- App: Kotlin and Jetpack Compose in `app/`; transport/protocol/domain code in `core/`.

## Public repository privacy

- Keep personal device addresses, SSH accounts, local checkout paths, proxy setup, and connection notes out of tracked files and generated public downloads.
- Use generic placeholders or reserved example addresses in documentation, UI hints, and test fixtures. Resolve local paths from environment variables or the current user's home directory.
- Before publishing, review the staged content for personal environment details as well as credentials.

## Build and verification

Use the project-local JDK, SDK, and Gradle wrapper script:

```sh
sh tools/gradle.sh :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Run `python3 tools/package_artifact.py` to package Orbit-named downloads. Verification should be proportional to the change. Integration tests use local SSH/RPC fixtures; live model probes are separate opt-in tasks.

## Agent boundaries

- App workspaces depend on `AgentRepository` and receive an `AgentEngineRegistry`; keep raw RPC and Codex wire methods inside the core adapter.
- Add engines in `AgentEngines.default`. Codex-compatible forks can reuse `CodexEngine`; different protocols implement `AgentEngine` and `AgentRepository`.
- Emit normalized `AgentEvent` / `TimelineUpdate` values and typed request kinds. Encode settings changes, request replies and review targets in the adapter.
- Reuse `WorkspaceRepository` / `SshWorkspace` for host services and `AgentTools` for tool operations.
- Keep existing profile serialization compatible: missing `engineId` means `codex`; retain the serialized `codexPath` field. Do not advertise unverified engines as supported.

## Compatibility

Public labels and new installation artifacts use **Orbit**. Keep Android application ID `dev.codexpocket`, vault preference/key names, stable RPC client ID, and existing Codex-adapter attachment/cache locations stable unless a deliberate data migration is implemented. A rename must preserve saved connections, encryption keys, open sessions, and drafts.
