# Plastic Insight

Plastic Insight is a small, inspector-first Rider VCS integration for Unity Version Control / Plastic SCM. It is not a replacement for the Plastic GUI or a complete Plastic client. Its first goals are the two workflows that matter most while coding:

1. show added, modified, and deleted lines in Rider's editor gutter;
2. show the history of the current file and allow revisions to be compared.

The project deliberately invokes `cm.exe` as independent processes. It does not depend on the persistent `cm shell` path used by the existing Unity Version Control plugin.

Plastic Insight implements only a narrow set of workspace actions through Rider's standard UI:

- **Add to VCS** adds the explicitly selected local items;
- **Rollback** runs Plastic undo for the explicitly selected changes after Rider's confirmation;
- deleting or moving a file is Rider's normal local filesystem operation, which Plastic Insight then reports;
- **Move to Another Changelist** changes Rider's local grouping only.

Check-in/commit, update, switch, merge, branches, shelves, locks, and repository administration are not implemented. Use the Plastic GUI or CLI for those workflows.

## Current state

The repository contains the standalone Rider plugin, a platform-independent Plastic command layer, tests, and the product documents. The current Rider adapter provides workspace mapping, local changes, unversioned files, gutter markers, diffs, exact add, and exact rollback. File history remains the next major user-visible increment.

## Prerequisites

- JetBrains Rider 2026.2 installed locally;
- Unity Version Control / Plastic SCM CLI (`cm.exe`);
- PowerShell 7 or Windows PowerShell;
- internet access on the first build so Gradle can resolve build dependencies.

The build resolves Rider in this order:

```text
-RiderHome, RIDER_HOME, %LOCALAPPDATA%\Programs\Rider
```

Gradle can also be invoked directly with `-PriderHome=C:/path/to/Rider`.

## Build from this folder

```powershell
.\build.ps1
```

Equivalent Gradle commands:

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

The installable archive is written to `build\distributions`.

To launch a disposable Rider instance with the plugin installed:

```powershell
.\build.ps1 runIde
```

## Documentation

- [Functional specification](Documentation/FunctionalSpecification.md)
