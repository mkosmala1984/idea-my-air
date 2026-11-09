# Deployment Architecture – My Air System

**Related High-Level View Section:** 2.4  
**Related Functional Requirements:** All

---

## 1. Overview

The My Air system consists of two separately built components, both built from this repository on the developer's machine:
1. **IntelliJ Plugin** – Built with Gradle into a ZIP and installed into the IDE from that ZIP
2. **Taskbar Widget** – Standalone Windows executable built with the dotnet CLI

The plugin works without the widget. The widget shows only what IDEs running the plugin write, and its clicks need a running IDE with the plugin.

No Marketplace publishing, signing, CI pipeline or installer is configured in the repository.

---

## 2. Plugin Deployment

### 2.1 Build Process

```
┌─────────────────────────────────────────────────────────────┐
│                      Plugin Build                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Source Code (Kotlin, src/main/kotlin)                      │
│  Resources (src/main/resources: META-INF/*.xml, icons/)     │
│       │                                                     │
│       ▼                                                     │
│  Gradle Build (./gradlew buildPlugin)                       │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Compile Kotlin (jvmToolchain 25, the IDE's JBR)   │    │
│  │ - Against the local IDE                             │    │
│  │   E:/dev/apps/jetbrains/IntelliJ IDEA Ultimate      │    │
│  │   and Branch Diff's built zip (localPlugin)         │    │
│  │ - Patch plugin.xml (sinceBuild 263)                 │    │
│  │ - Create JAR my-air-0.1.0.jar                       │    │
│  └─────────────────────────────────────────────────────┘    │
│       │                                                     │
│       ▼                                                     │
│  ZIP Distribution                                           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ build/distributions/my-air-0.1.0.zip                │    │
│  │   my-air/lib/my-air-0.1.0.jar                       │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

Branch Diff's zip (`E:/dev/projects/idea-branch-diff/build/distributions/branch-diff-tree-0.1.0.zip`) must be built first with that project's `buildPlugin`. Searchable options are not built (`buildSearchableOptions = false`).

### 2.2 Build Commands

| Command | Purpose |
|---------|---------|
| `./gradlew test buildPlugin` | Run all tests and build the plugin ZIP |
| `./gradlew test --tests <class or test>` | Run one test class or test |
| `env $(env \| grep -o '^CLAUDE[A-Z_]*' \| sed 's/^/-u /') ./gradlew --no-daemon runIde` | Run the sandbox IDE without the `CLAUDE*` variables of the launching Claude Code session |

### 2.3 Distribution Channels

| Channel | Method |
|---------|--------|
| **Install My Air Build and Restart** | Tools menu action (`InstallMyAirBuildAction`), shown only in a project with exactly one `build/distributions/my-air-*.zip`: `PluginInstaller.installAfterRestart` over the running My Air, then an IDE restart without the platform's question; the exit confirmation asks first when a session it would end works |
| **Local install** | The ZIP installed through Settings › Plugins › Install Plugin from Disk |
| **Sandbox** | `runIde` loads the build (and Branch Diff) into `.intellijPlatform/sandbox/my-air/IU-2026.3`; log in `log_runIde/idea.log`; restart it after `buildPlugin` |

### 2.4 Versioning

- **Version:** `0.1.0` (`version` in `build.gradle.kts`), with no build-number or platform suffix
- **Plugin id:** `dev.myair`

### 2.5 Requirements

| Requirement | Detail |
|-------------|--------|
| **IDE Version** | IntelliJ IDEA build 263+ (2026.3) |
| **Java** | Java 25 (JBR bundled with IDE) |
| **Platform** | Windows only |
| **Dependencies** | Terminal plugin (required); MCP Server and Branch Diff (optional) |
| **Tools on PATH** | `claude`; `git` for the Git features; `plannotator.exe` optional |

---

## 3. Taskbar Widget Deployment

### 3.1 Build Process

```
┌─────────────────────────────────────────────────────────────┐
│                    Taskbar Widget Build                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Source Code (C#, WPF, taskbar/)                            │
│       │                                                     │
│       ▼                                                     │
│  pwsh taskbar/build.ps1                                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ - Stop a running MyAirTaskbar (it locks its exe)    │    │
│  │ - dotnet test taskbar/tests (xUnit)                 │    │
│  │ - dotnet build -c Release taskbar (NuGet restore)   │    │
│  │ - Start the new build                               │    │
│  └─────────────────────────────────────────────────────┘    │
│       │                                                     │
│       ▼                                                     │
│  Output                                                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ taskbar/bin/Release/net10.0-windows10.0.26100.0/    │    │
│  │   win-x64/MyAirTaskbar.exe and its dependencies     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Build Commands

| Command | Purpose |
|---------|---------|
| `pwsh taskbar/build.ps1` | Stop the running widget, test, build Release, start it |
| `dotnet test taskbar/tests` | Widget tests only |
| `dotnet build -c Release taskbar` | Build only |

### 3.3 Requirements

| Requirement | Detail |
|-------------|--------|
| **OS** | Windows 11 (target `net10.0-windows10.0.26100.0`, minimum 10.0.26100.0, win-x64) |
| **Runtime** | .NET 10 |
| **Framework** | WPF |
| **Dependencies** | Deskband11Lib.Wpf 1.4.2 |

### 3.4 Installation Methods

| Method | Description |
|--------|-------------|
| **Manual** | Run `MyAirTaskbar.exe` from the build output (`build.ps1` starts it); a second copy exits at once (mutex `Local\MyAir.Taskbar`) |

---

## 4. Shared State Deployment

### 4.1 State Directory

```
~/.my-air/
├── ides/                    # IDE tab state (per instance)
├── requests/                # Focus requests (per instance)
├── pins.json                # Pinned/settled sessions (shared)
├── structure.json           # Folder structure (shared)
├── history/                 # Structure snapshots (shared)
├── presets.json             # Session configurations (shared)
├── seen/                    # Last seen timestamps (shared)
├── taskbar/                 # Taskbar board data (shared)
│   └── board.json
├── images/                  # Pasted prompt images (shared)
├── hooks/                   # brief-reminder.json settings (shared)
├── prompts/                 # Plan prompt files (per IDE pid)
└── scan-cache-<hash>.json   # Scan offsets (per plugin jar path)
```

### 4.2 Permissions

| Path | Read | Write | Owner |
|------|------|-------|-------|
| `~/.claude/` | All | None | Claude Code |
| `~/.my-air/` | All | All | All IDE instances and the widget |

### 4.3 Initialization

- **First run:** Create `~/.my-air/` and its directories if they do not exist
- **Subsequent runs:** Use existing state
- **Format versions:** `structure.json` is read in versions 1–4 and written as 4; the scan cache is tagged with a hash of the transcript-reading classes and re-read whole when they change

---

## 5. Update Strategy

### 5.1 Plugin Updates

- **Manual:** Build a new ZIP and run Install My Air Build and Restart, or install the ZIP from disk

### 5.2 Taskbar Widget Updates

- **Replace:** `build.ps1` stops the old process, builds and starts the new one

### 5.3 State Compatibility

- **Forward compatible:** New versions can read old state
- **Backward compatible:** Old versions may not read new state (a My Air older than `structure.json` version 4 cannot read the file once a newer one writes it)
- **Migration:** On read, older `structure.json` versions are converted (version 2's `project` reads as one bound directory)

---

## 6. Packaging

### 6.1 Plugin Package Contents

```
my-air-0.1.0.zip
└── my-air/
    └── lib/
        └── my-air-0.1.0.jar   # Classes, META-INF/plugin.xml, my-air-mcp.xml,
                               # my-air-branch-diff.xml, icons/
```

### 6.2 Widget Package Contents

```
taskbar/bin/Release/net10.0-windows10.0.26100.0/win-x64/
├── MyAirTaskbar.exe                 # Main executable
├── MyAirTaskbar.dll                 # Main assembly
├── MyAirTaskbar.deps.json
├── MyAirTaskbar.runtimeconfig.json
├── Deskband11Lib.Wpf.dll            # Taskbar hosting library
├── Deskband11Lib.Core.dll
├── Microsoft.Windows.SDK.NET.dll
└── WinRT.Runtime.dll
```

---

## 7. Traceability

| Deployment Aspect | FR Coverage |
|-------------------|-------------|
| Plugin Build | All |
| Widget Build | FR-TW-* |
| State Directory | FR-CI-*, FR-AN-* |
| Updates | All |
| Packaging | All |
