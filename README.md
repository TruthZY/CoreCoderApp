# CoreCoder Android

AI-powered coding assistant for Android, featuring a tool-calling agent with embedded Linux execution environment.

## Architecture

```
app/                          # Main Android application
├── core/
│   ├── Agent.kt              # Tool-calling AI agent loop
│   ├── LLMClient.kt          # SSE streaming LLM client
│   ├── ContextManager.kt     # Conversation context & compression
│   ├── PromptBuilder.kt      # System prompt construction
│   ├── SkillManager.kt       # Two-phase skill injection
│   └── exec/
│       ├── CommandExecutor.kt          # Pluggable command execution interface
│       ├── OperitCommandExecutor.kt    # Bridge to terminal-core
│       ├── ShellCommandExecutor.kt     # Direct shell execution
│       ├── EnvironmentBootstrap.kt     # Proot/Ubuntu environment setup
│       └── TarExtractor.kt             # Pure-Java rootfs extraction
├── tools/                    # AI agent tools (bash, read, write, edit, grep, glob)
├── ui/                       # Jetpack Compose UI (Material 3)
├── data/                     # Room database (conversations, messages, skills)
├── di/                       # Hilt dependency injection
└── viewmodel/                # ViewModels

terminal-core/                # Git submodule → OperitTerminalCore
                              # Provides: proot, bash, busybox, Ubuntu 24.04 rootfs
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| DI | Hilt / Dagger |
| DB | Room |
| Network | OkHttp SSE + Retrofit + Gson |
| Execution | proot + Ubuntu 24.04 (aarch64) |
| Native | CMake (PTY) |
| Min SDK | 26 (Android 8.0) |
| Target | arm64-v8a only |

## Build

```bash
# Debug
./gradlew assembleDebug

# Release (signed with debug keystore)
./gradlew assembleRelease
```

## TODO

### 🔴 Critical — Execution Stack

- [ ] **Fix hidden exec shell zero output** — Bash process starts but produces no stdout on real arm64 device. Both hidden exec and visible PTY fail. Likely root cause: proot probe failure, bash crash during startup, or environment variable issue. Diagnostic logging added to `LocalTerminalProvider`, awaiting logcat capture on device.
- [ ] **Verify `install_ubuntu` rootfs extraction** — Ensure the Ubuntu rootfs tar.xz is correctly extracted on first launch. The `install_ubuntu` shell function uses `busybox tar xf`; verify it completes successfully and `$UBUNTU_PATH` is populated.
- [ ] **Fix PTY visible terminal EIO error** — PTY sessions fail with `EIO` (I/O error) immediately after start, with zero output received. May share root cause with hidden exec failure.

### 🟡 Important — Stability

- [ ] **Replace debug keystore signing** — Release builds currently use `~/.android/debug.keystore`. Set up proper signing configuration with a release keystore.
- [ ] **Handle rootfs first-launch delay** — Ubuntu rootfs extraction (~64 MB tar.xz) can take significant time on first launch. Add user-facing progress indicator.
- [ ] **Error recovery for shell session** — Implement automatic retry when hidden exec shell fails to initialize, with exponential backoff.

### 🟢 Enhancement

- [ ] **Add unit/integration tests** — Current test coverage is minimal. Add tests for agent loop, tool execution, and command executor.
- [ ] **Support multiple LLM providers** — Extend settings to support OpenAI, Anthropic, and other providers beyond current SSE-based implementation.
- [ ] **Context window optimization** — Improve context compression strategy for longer conversations.
- [ ] **File system access UI** — Add file browser integration for the AI to navigate and inspect project files visually.
- [ ] **Offline model support** — Explore on-device LLM inference for basic tasks without network.
