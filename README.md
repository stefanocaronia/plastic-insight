# Plastic Insight

[![Build](https://github.com/stefanocaronia/plastic-insight/actions/workflows/build.yml/badge.svg)](https://github.com/stefanocaronia/plastic-insight/actions/workflows/build.yml)

Plastic Insight is a lightweight, inspector-first Rider integration for Unity Version Control / Plastic SCM. It brings the Plastic information needed while coding into Rider without trying to replace the Plastic GUI or become a complete Plastic client.

## Features

- native added, modified, and deleted line markers in Rider's editor gutter;
- Plastic changes and unversioned files in Rider's **Local Changes** view;
- byte-accurate base revisions and Rider's standard side-by-side diff;
- native file history with lazy revision content and revision comparison;
- bounded history loading: 50 revisions initially, then 200 or 999 only on request;
- exact **Add to VCS** and confirmed **Rollback** for explicitly selected items;
- moved, deleted, added, private, Unicode, and externally changed files.

Plastic commands run as bounded, cancellable, one-shot `cm.exe` processes. The plugin has no recurring Plastic polling loop, persistent shell, background executor, or unbounded cache.

## Product boundary

Plastic Insight is not a check-in client. Check-in/commit, update, switch, merge, branches, shelves, locks, and repository administration are intentionally not implemented. Use the Plastic GUI or CLI for those workflows.

Deleting or moving a file remains Rider's normal local filesystem operation, which Plastic Insight reports afterward. **Move to Another Changelist** changes only Rider's local grouping.

## Requirements and compatibility

- Windows with Unity Version Control / Plastic SCM CLI (`cm.exe`);
- JetBrains Rider 2026.2 (`262.*` platform builds);
- an existing Plastic workspace mapped to **Plastic Insight** in Rider's Version Control settings.

This first release is Windows-only. Linux is not advertised as supported because executable discovery and the development packaging flow have not been validated there. PowerShell is required only when building from source, not when installing the ZIP.

`cm.exe` is resolved from `CM_EXE`, the standard Plastic installation folders, or `PATH`.

## Install

1. Download `plastic-insight-0.1.0.zip` from the GitHub Release.
2. In Rider open **Settings | Plugins**, use the gear menu, and select **Install Plugin from Disk**.
3. Select the ZIP and restart Rider when requested.
4. Open **Settings | Version Control** and map the Plastic workspace root to **Plastic Insight**.

## Build from source

The build targets a locally installed Rider SDK. It resolves Rider from `-RiderHome`, `RIDER_HOME`, or `%LOCALAPPDATA%\Programs\Rider`.

```powershell
.\build.ps1
```

Equivalent commands:

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

The installable archive is written to `build\distributions`. To launch a disposable Rider instance:

```powershell
.\build.ps1 runIde
```

GitHub Actions runs the same tests and plugin verification on every push and pull request. A matching `v*` tag builds the package and publishes it automatically as a GitHub Release asset.

## Documentation

- [Functional specification](Documentation/FunctionalSpecification.md)
