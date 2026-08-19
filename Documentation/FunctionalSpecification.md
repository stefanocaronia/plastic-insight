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
- **Inspector first:** observation and comparison remain the product focus; a small workspace action is added only when Rider supplies the correct UX and the Plastic command can be kept explicit, bounded, and cancellable.
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

Implementation baseline as of 2026-08-19: the core first locates the nearest `.plastic/plastic.workspace` marker by walking parent directories, then executes the shell-free `getworkspacefrompath` contract only for that candidate root. The fixture-tested parser returns immutable workspace metadata, normalizes Windows drive-letter casing, distinguishes static and dynamic workspaces, and rejects malformed or inconsistent results. Workspace metadata is held in a bounded LRU cache with explicit invalidation and disposal; typed failures distinguish missing or invalid executables, launch/execution failures, timeouts, cancellation, truncation, command failures, and malformed output. Rider root-change wiring remains part of the native VCS slice.

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

Implementation baseline as of 2026-08-19: the core keeps ordinary status queries strictly inside the workspace and exposes a separate root-refresh contract with explicit controlled-change/deleted/moved filters. Private discovery is enabled only together with `--ignored --cutignored`, which returns ignored markers without traversing their contents, and root stdout remains capped at 1 MiB. A representative read-only root run returned 391 framed records and approximately 42 KiB in 0.95 seconds: 15 controlled/private records plus 375 ignored markers that the Rider adapter discards. The workspace baseline is retrieved through `cm cat <absolute-path>#cs:<workspace-changeset> --raw` into a bounded binary result without text conversion or temporary files. The status parser performs a single framing pass with per-record cancellation checks, accepts the CLI's CR/LF separators between framed records, validates the header, revision IDs, and current/old path containment, preserves combined status codes, and distinguishes ordinary records from moves. Malformed, unknown, truncated, or non-zero results fail explicitly instead of being interpreted as a clean workspace. Baseline bytes use defensive copies and a bounded cache; files larger than the per-entry cache limit are returned but not retained. Read-only validation on a representative workspace completed in approximately 0.5 seconds for a 1.4 KiB baseline file. The verified working file carries a local UTF-8 BOM that Plastic omits from `cat --raw`; the remaining bytes match exactly, so Rider integration must avoid inventing or normalizing repository bytes while testing that this metadata-only difference does not create a false line marker.

The first native Rider slice registers Plastic Insight as a centralized VCS and recognizes only exact roots containing both Plastic workspace markers, without invoking the CLI. Its `ChangeProvider` uses exact non-directory dirty files for ordinary refreshes and coalesces broad/recursive refreshes to dominant affected roots, then maps modified, added, deleted, and moved Plastic states to native Rider revisions; checkout alone is not reported as a modification. Its `DiffProvider` supplies a byte-backed baseline, uses Rider's BOM-aware decoding only when a text consumer requests it, and overrides the current-content path so one baseline load performs one status call rather than two. Gateway creation is lazy, cached even on failure, and coordinated with project shutdown; all Plastic calls remain cancellable and off the event-dispatch thread. No Rider-side service, scheduler, listener, executor, cache, polling loop, or custom gutter painter was introduced.

The initial refresh now uses one filtered root status instead of enumerating the project tree or launching one process per file, so Rider's Changes view can receive controlled and non-ignored private files immediately after mapping. Ordinary small dirty sets retain exact-file queries; larger or recursive sets are coalesced. Every private-inclusive query uses `--ignored --cutignored`; `PR` records are handed to Rider as unversioned files, `IG` markers are ignored, and neither receives an invented revision or baseline. Broad refreshes retain at most four exact probes in addition to the single bounded root query. When Plastic reports a moved destination as private to an exact query, the provider gives precedence to an already known controlled record or performs at most one method-local, controlled-only root fallback per workspace; this prevents duplicate moved/unversioned entries without retaining status state. Manual mapped-workspace tests on 2026-08-19 confirmed the native modified-line gutter, a populated controlled-changes list, Rider's byte-backed side-by-side diff, and save-driven refresh without plugin polling. A separate temporary file with a non-ASCII name, created and deleted outside Rider, appeared automatically as unversioned and then disappeared; this also validates machine-readable exact-file status decoding for that path. The `ChangeProvider` reports no state for unsupported cases, while baseline retrieval errors remain explicit and never return synthetic content bytes.

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

Implementation baseline as of 2026-08-19: the core builds bounded XML history, historical-path lookup, and `serverpath:` raw-content invocations. Forward-only StAX parsers consume captured UTF-8 bytes directly, reject DTD/external entities, enforce entry limits, preserve Unicode and multiline comments, model checkout and metadata-only events, and treat zero/one/multiple historical lookups as not-found/found/ambiguous. History is exposed newest-first as a bounded initial view with one-record lookahead and explicit `hasMore`; `cm history` has no verified cursor or offset, so this is intentionally bounded loading rather than simulated native pagination. Historical paths and selected content use bounded LRU caches, content is fetched lazily, and unavailable, archived, purged, ambiguous, malformed, timed-out, truncated, and cancelled outcomes remain distinct. Live read-only gateway validation succeeded end to end for discovery, status, history, lookup, and historical bytes. Cloud repositories may identify the same server by an ID-style `@cloud` address in history and a canonical organization `@unity` alias in revision lookup; the gateway therefore validates item, changeset, repository, and absolute server path without requiring those equivalent server strings to match. The complete opt-in gateway flow now also passes against a representative cloud file exhibiting this alias pair. The Rider adapter registers a native `VcsHistoryProvider`, maps the 50 newest entries to standard author/date/comment/branch rows, keeps metadata-only events visible but non-comparable, and loads available file bytes only when Rider requests a comparison. When `hasMore` is true, a toolbar action expands the same bounded view deliberately from 50 to 200 and finally 999 revisions; every step is a fresh prefix query, and the history cache replaces the shorter prefix instead of retaining overlapping copies. Rider 2026.2 omits iconless provider actions from the history toolbar, so the expansion action supplies a standard platform icon. A pending move destination is reported as private by exact Plastic queries, while neither its new nor old local filesystem path can be passed directly to `cm history`. After that specific exit-1 result, the gateway performs one bounded controlled-root status, resolves the move's old server path at the loaded workspace changeset, and retries through a single `serverpath:` argument. The successful query is retained inside the existing bounded history-cache entry, so later expansion does not repeat the failed path or status call. A separate opt-in read-only integration test passes on a real pending move destination. Historical content across renames is then resolved by stable item ID and changeset before `cm cat`, rather than trusting a stale path embedded in a revision spec. Rider's default history diff handler compares a selected revision with the working copy or another revision without a custom diff UI. A Plastic Insight submenu exposes the standard history action in Rider's editor, project, and Changes context menus. Manual mapped-workspace validation confirms the submenu, initial rows, selected-revision comparison, and corrected pending-move history after the cloud-alias and move-path corrections. Only the corrected explicit 50 → 200 long-history expansion action still awaits a dedicated Rider smoke test for this slice.

### 4.4 Limited workspace actions

Plastic Insight is not a full Plastic client. The initial writable surface is deliberately limited to actions that fit Rider's standard local-changes UI.

- FR-040: “Add to VCS” shall add only explicitly selected items and shall never imply a recursive workspace scan.
- FR-041: “Rollback” shall undo only explicitly selected changes after Rider's normal confirmation.
- FR-042: every mutating command shall remain shell-free, cancellable, output-bounded, and strictly inside its mapped workspace.
- FR-043: a failed, cancelled, or timed-out multi-item mutation shall invalidate read caches because Plastic may have changed a subset before failing.
- FR-044: check-in/commit and update actions shall remain disabled until their complete workflows are implemented.

Implementation baseline as of 2026-08-19: the standard Rider `CheckinEnvironment` exposes only exact `cm add`, while `RollbackEnvironment` maps selected modified, added, deleted, and moved changes to exact non-recursive `cm undo`. Parent paths are ordered before children for add; workspace roots and outside paths are rejected. Rider's ordinary file deletion and move operations remain local filesystem actions, and “Move to Another Changelist” remains Rider-local grouping. No persistent service, queue, cache, or process was added for these user-initiated actions. Manual Rider validation confirmed that Add moves a private item into controlled changes and Rollback restores the expected Plastic workspace state through Rider's standard confirmation flow.

### 4.5 Diagnostics and settings

- FR-030: automatically resolve `cm.exe` from an explicit setting, `CM_EXE`, common installation locations, or `PATH`.
- FR-031: allow a configurable command timeout.
- FR-032: provide a lightweight connection test showing CLI version and detected workspace.
- FR-033: log command names, duration, and exit status without leaking credentials or full file content.
- FR-034: show actionable errors rather than silent empty views.

Implementation baseline as of 2026-08-19: the core resolves `cm.exe` once in the order explicit setting, `CM_EXE`, common 64-bit and 32-bit installation paths, then `PATH`, without recursive filesystem scans. Runtime settings carry a positive command timeout and a canonical non-workspace directory for historical queries. Privacy-safe diagnostics retain only operation, origin, outcome, duration, exit code, and byte counts; they never retain arguments, paths, stderr text, XML, or file content. Rider settings UI, notifications, and the connection-test action remain later adapter work.

## 5. Out of scope for the MVP

- checkin/commit and shelve workflows;
- update, switch, merge, and branch creation;
- broad, recursive, or administrative workspace mutations;
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

The core process runner currently implements NFR-007 and NFR-012 by draining both process streams while retaining only their command-specific byte limits. It reports total bytes read and truncation separately, and a truncated result cannot be successful. Default limits are 8 MiB for text/XML, 32 MiB for revision content, 256 KiB for stderr, and 64 KiB for workspace discovery. Each invocation isolates the two blocking stream readers on short-lived virtual threads, polls only while that invocation is active, and applies bounded graceful/forceful cleanup to the process tree on timeout, cancellation, interruption, or disposal. No executor or process survives as idle plugin state. Workspace, history, historical-path, and byte-content caches are LRU bounded by both entry count and estimated retained bytes; lifecycle generations prevent an in-flight result from repopulating a cache after invalidation or disposal.

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

As of 2026-08-19, 137 automatic Kotlin/JUnit tests pass and two read-only live gateway tests are skipped unless explicitly enabled; both opt-in flows also pass separately against explicitly selected representative Plastic files, including a pending move destination. `test`, `buildPlugin`, `verifyPluginProjectConfiguration`, and `verifyPluginStructure` pass against the local Rider 2026.2 SDK and JetBrains Runtime 25. Pure tests do not depend on Rider's bundled test framework; that dependency will be introduced only when IntelliJ fixture tests require it. The initial public package is `build/distributions/plastic-insight-0.1.0.zip`, with JetBrains light/dark plugin icons and a Rider-visible description that states the inspector-first boundary and the project's independent, unofficial status. Source and binary distributions carry the Apache License 2.0; the repository also discloses AI-assisted development while assigning review and release responsibility to the maintainers. Release 0.1.0 deliberately targets Windows and Rider platform builds `262.*`; broader Rider and operating-system compatibility require separate validation. Project verification emits the expected recommendation to remove that deliberate upper compatibility bound. GitHub Actions uses the same Gradle wrapper with Java 25, a downloadable Rider 2026.2 multi-OS SDK, Plugin Verifier, artifact retention, and tag-gated GitHub Release creation; local development continues to reuse the installed Rider SDK.

## 10. Future capability map

Potential later increments include annotations/blame, changed-file tree improvements, external change watching, locks, branches, shelves, changelist-aware checkin, multi-repository workspaces, remote-development support, and Marketplace packaging.

Every additional mutating feature must be designed as an explicit command with confirmation, cancellation, failure recovery, and integration tests against a disposable workspace. No mutating validation may be run against a user's existing workspace by an agent.
