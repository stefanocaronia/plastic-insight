---
title: Plastic Insight Functional Specification
status: Draft
version: 0.1
created: 2026-08-18
updated: 2026-08-19
---

# Plastic Insight Functional Specification

## 1. Purpose

Plastic Insight provides dependable, native-looking Plastic SCM information inside JetBrains Rider. The initial release focuses on editor feedback and file history, while keeping the architecture suitable for broader Plastic workflows later.

The plugin is intended to work even when Unity Version Control's existing Rider integration can list pending changes but cannot materialize a base revision for Rider's diff viewer.

## 2. Product principles

- **Native Rider behavior:** use IntelliJ VCS APIs so Rider owns gutter rendering, diff presentation, refresh, and history UI where possible.
- **Direct Plastic access:** execute finite `cm.exe` commands and consume their result; do not keep a `cm shell` process alive.
- **Read-only first:** the MVP observes repository state and retrieves content but does not mutate a workspace or repository.
- **Testable core:** CLI arguments, parsers, path mapping, and domain models remain independent of Rider.
- **Incremental growth:** future capabilities extend explicit interfaces instead of replacing the foundation.

## 3. Primary users and environment

The initial user is a Windows developer using:

- Rider 2026.2;
- Unity projects in Plastic workspaces;
- Plastic SCM CLI 11.x;
- potentially large projects with generated and binary assets.

The design must not assume that all future users install Rider or Plastic in the same location.

## 4. MVP scope

### 4.1 Workspace discovery

When Rider opens a project or content root, Plastic Insight shall determine whether it belongs to a Plastic workspace and find its workspace root.

Requirements:

- FR-001: invoke Plastic discovery from the relevant project directory;
- FR-002: support nested project directories inside a workspace;
- FR-003: report a clear, non-blocking diagnostic if `cm.exe` is unavailable;
- FR-004: avoid claiming directories that are not Plastic workspaces;
- FR-005: cache stable workspace metadata and invalidate it when roots change.

Implementation baseline as of 2026-08-19: the core can build and execute the shell-free `getworkspacefrompath` contract with absolute execution and target paths, `--extended`, and six fields separated by U+001F. The raw contract was validated read-only against two representative regular/static workspaces. A fixture-tested parser now returns immutable workspace metadata, normalizes Windows drive-letter casing, distinguishes static and dynamic workspaces, and rejects malformed field counts, identifiers, paths, and modes with a typed parse exception. Command failure mapping and caching remain required before FR-001 through FR-005 are complete.

### 4.2 Editor gutter changes

For a versioned text file, Rider shall show its normal line-status indicators beside the editor:

- green for added lines;
- blue for modified lines;
- a deletion marker at the line where content was removed.

Requirements:

- FR-010: supply Rider with the pristine/base content of the current Plastic revision;
- FR-011: refresh after file saves and external Plastic state changes;
- FR-012: avoid blocking the UI thread while invoking Plastic;
- FR-013: handle added, changed, deleted, moved, and unversioned files predictably;
- FR-014: let Rider calculate and render line differences instead of maintaining a custom editor painter;
- FR-015: show no misleading gutter state when base content cannot be retrieved.

Implementation baseline as of 2026-08-19: the core builds constrained status invocations and retrieves the workspace baseline through `cm cat <absolute-path>#cs:<workspace-changeset> --raw` into a bounded binary result without text conversion or temporary files. The status parser performs a single framing pass, validates the header and revision IDs, preserves combined status codes, and distinguishes ordinary records from moves carrying old/new paths. Malformed or unknown records fail explicitly instead of being interpreted as a clean workspace. Read-only validation on a representative workspace completed in approximately 0.5 seconds for a 1.4 KiB baseline file. The verified working file carries a local UTF-8 BOM that Plastic omits from `cat --raw`; the remaining bytes match exactly, so Rider integration must avoid inventing or normalizing repository bytes while testing that this metadata-only difference does not create a false line marker.

### 4.3 File history

From Rider's file context actions, the user shall be able to open the history of a versioned file.

Each history row shall expose, when Plastic provides it:

- changeset or revision identifier;
- author;
- date and time;
- comment;
- branch or repository context.

Requirements:

- FR-020: load history for the selected workspace-relative path;
- FR-021: fetch a selected historical revision on demand;
- FR-022: compare a selected revision with the working copy or another revision using Rider's diff viewer;
- FR-023: support pagination or bounded loading for long histories;
- FR-024: preserve rename/move context when Plastic exposes it;
- FR-025: cancellation shall terminate or abandon the associated CLI process safely.

Implementation baseline as of 2026-08-18: the core builds bounded XML history, historical-path lookup, and `serverpath:` raw-content invocations. Live read-only validation succeeded for history, `find revision`, and historical `cat` on a representative workspace. Typed XML parsing, paging state, cancellation, and Rider sessions remain outstanding.

### 4.4 Diagnostics and settings

- FR-030: automatically resolve `cm.exe` from an explicit setting, `CM_EXE`, common installation locations, or `PATH`.
- FR-031: allow a configurable command timeout.
- FR-032: provide a lightweight connection test showing CLI version and detected workspace.
- FR-033: log command names, duration, and exit status without leaking credentials or full file content.
- FR-034: show actionable errors rather than silent empty views.

## 5. Out of scope for the MVP

- checkin and shelve workflows;
- update, switch, merge, branch creation, and workspace mutation;
- lock acquisition or release;
- Plastic GUI replacement;
- custom diff algorithms;
- semantic C# analysis or ReSharper backend integration;
- Marketplace publication and automatic updates.

These items remain valid future extensions but require separate acceptance criteria and explicit authorization because they can mutate user data or remote state.

## 6. UX behavior

### 6.1 Normal operation

Plastic Insight should be mostly invisible. Once a Plastic root is mapped, Rider's existing VCS surfaces display changes, gutter markers, diff actions, and history actions using familiar controls.

### 6.2 Empty and error states

- Not a workspace: do not map Plastic and do not show repeated warnings.
- CLI missing: show one actionable notification and retain details in the IDE log.
- Command timeout: allow retry and preserve the editor's responsiveness.
- File not versioned: present an empty history with an explicit “not versioned” explanation.
- Base revision unavailable: omit gutter markers for that refresh and expose a diagnostic; never invent a clean state.

## 7. Non-functional requirements

- NFR-001: no Plastic process may block Rider's event-dispatch thread.
- NFR-002: ordinary status and base-content queries should complete within two seconds on a local workspace under normal conditions.
- NFR-003: CLI output parsers shall be covered by fixture-based unit tests, including spaces, Unicode, renames, empty comments, and malformed output.
- NFR-004: the core package shall not import IntelliJ classes.
- NFR-005: the plugin shall be buildable and testable from the repository root with the Gradle Wrapper.
- NFR-006: all temporary revision files shall be owned, uniquely named, and cleaned reliably. Prefer in-memory bytes when the Rider API permits them.
- NFR-007: cancellation and disposal shall not leave persistent `cm.exe` processes.
- NFR-008: command output used as file content shall preserve bytes and line endings.
- NFR-009: when Rider is idle, Plastic Insight shall not poll Plastic, retain an active process, or consume recurring CPU solely to keep status warm.
- NFR-010: status refreshes shall be debounced, coalesced by workspace, and single-flight; an obsolete queued or running refresh shall be cancelled or its result discarded.
- NFR-011: every cache shall have explicit entry-count and retained-byte bounds, an eviction policy, and disposal/invalidation hooks. No cache may grow monotonically with files, revisions, or workspaces visited.
- NFR-012: CLI stdout and stderr capture shall be bounded. Exceeding a command-specific limit shall produce a typed failure without retaining the discarded bytes in memory.
- NFR-013: project disposal shall release listeners, executors, queued work, process handles, and references to IntelliJ `Project` or `VirtualFile` instances.
- NFR-014: diagnostics shall record command duration, output byte count, cache hits, coalescing, and cancellations without recording file content.
- NFR-015: tracked documentation and test fixtures shall use synthetic workspace, repository, server, path, and identifier data. Details of live validation environments remain local and unversioned.

The core process runner currently implements NFR-012 by draining both process streams while retaining only their command-specific byte limits. It reports total bytes read and truncation separately, and a truncated result cannot be successful. Default limits are 8 MiB for text, 32 MiB for revision content, 256 KiB for stderr, and 64 KiB for workspace discovery. Each invocation isolates the two blocking stream readers on short-lived virtual threads and closes their executor deterministically instead of occupying Rider's shared fork-join pool.

## 8. Logical data flow

```text
Rider VCS request
    -> Rider adapter
    -> Plastic use case
    -> CLI command builder
    -> one-shot cm.exe process
    -> typed parser/domain model
    -> Rider adapter
    -> Rider gutter, diff, or history UI
```

The same command and parser layer can later serve a command-line diagnostic harness or additional IDE frontends.

## 9. Acceptance criteria for the first usable milestone

Using a real Plastic workspace in Rider 2026.2:

1. Plastic Insight recognizes the workspace without the Unity Version Control Rider plugin being enabled.
2. Editing an existing text line produces a blue Rider gutter marker.
3. Adding lines produces green markers.
4. Deleting lines produces Rider's deletion marker.
5. The editor diff opens even when the official integration would fail to create its temporary base file.
6. “Show History” lists accurate revisions, authors, dates, and comments for a selected file.
7. A historical row can be compared with the working copy.
8. No persistent `cm shell` process exists after the operations.
9. Unit tests and `buildPlugin` pass from the project root.

### 9.1 Current build-validation baseline

As of 2026-08-18, the pure Kotlin/JUnit unit-test suite and `buildPlugin` pass against the local Rider 2026.2 SDK and its JetBrains Runtime 25. Pure unit tests do not depend on Rider's bundled test framework; that dependency will be introduced only when IntelliJ fixture tests require it. The current installable artifact is `build/distributions/plastic-insight-0.1.0-SNAPSHOT.zip`.

## 10. Future capability map

Potential later increments include annotations/blame, changed-file tree improvements, revision-to-revision comparison, external change watching, locks, branches, shelves, changelist-aware checkin, multi-repository workspaces, remote-development support, and Marketplace packaging.

Every mutating feature must be designed as an explicit command with confirmation, cancellation, failure recovery, and integration tests against a disposable workspace.
