---
title: Plastic Insight Project Structure
status: Active
created: 2026-11-11
updated: 2026-11-11
---

# Project Structure

What each folder holds, what it may depend on, and where new code goes. This page changes in the same change that adds, moves, or removes a folder.

## Top level

| Path | Holds |
| --- | --- |
| `src/main/kotlin/com/teamcomplex/plasticinsight/` | The plugin code: `core` and `rider` (see below). |
| `src/main/resources/META-INF/` | `plugin.xml` and the plugin icons. |
| `src/test/kotlin/`, `src/test/java/` | Tests, in the same packages as the code they test. The Java file is a child process used by the process-runner tests. |
| `src/test/resources/fixtures/` | Recorded `cm` output with synthetic identifiers, one folder per command. |
| `Documentation/` | The versioned docs, listed in [index.md](index.md). `Documentation/Plans/` is local and ignored by Git. |
| `.github/workflows/` | The build, signing, and release workflow. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | The Gradle build; the plugin version lives in `gradle.properties`. |
| `build.ps1` | Local build that uses the installed Rider and its bundled runtime. |

## Layers

```text
rider        Rider and IntelliJ adapters
  -> core    PlasticGateway: the operations, their results, caches
     -> core/workspace, core/status, core/history   typed models and parsers
     -> core/cli         cm commands
        -> core/process  one bounded cm.exe process
     -> core/parsing     shared parsing helpers
```

A package depends only on the ones below it. Nothing under `core` imports IntelliJ or Rider classes, and no subpackage of `core` imports from `core` itself.

## `core`

Package `com.teamcomplex.plasticinsight.core`. Independent of Rider: it runs and is tested without an IDE.

| Package | Holds | Depends on |
| --- | --- | --- |
| `core` | `PlasticGateway` (the only entry point for `rider`) and its factory; `DefaultPlasticGateway` holds the operations, `GatewayCaches` the bounded caches, `GatewayResults` the results and their diagnostics. Also `PlasticResult` and `PlasticFailure`, runtime settings, executable resolution. | every subpackage |
| `core.process` | One bounded, one-shot process: the invocation, the runner, its result, output capture, cancellation. | nothing |
| `core.cli` | The `cm` commands: `PlasticCommandBuilder` builds the arguments, `PlasticCli` runs them and returns text or bytes. | `core.process` |
| `core.parsing` | Helpers shared by the parsers: the XML cursor, Plastic path parsing, `PlasticParseException`. | nothing |
| `core.workspace` | The workspace model, the marker-based root locator, the discovery parser. | `core.parsing` |
| `core.status` | The pending-change model and the status parser. | `core.parsing` |
| `core.history` | The file-history and historical-revision models and their XML parsers. | `core.parsing` |

## `rider`

Package `com.teamcomplex.plasticinsight.rider`. Everything that touches IntelliJ or Rider: `PlasticVcs` and the providers it registers (changes, diff, history, add and rollback), plus the root checker. It calls `core` only through `PlasticGateway`: `PlasticGatewaySession` owns the project's gateway and turns its typed failures into Rider exceptions. Decisions that do not need the IDE are plain functions next to their provider (`planStatusScopes`, `baselinePath`, `runPerRoot`), so they are tested without a Rider instance. The classes named in `plugin.xml` (`PlasticVcs`, `PlasticRootChecker`, `PlasticVcsMenu`) cannot move or be renamed without changing `plugin.xml` in the same change.

## Where new code goes

- A new `cm` command: its arguments in `core.cli` (`PlasticCommandBuilder`, then `PlasticCli`), its model and parser in the subpackage of its area (a new one if the area is new), and the operation on `PlasticGateway`.
- A new Rider feature: an adapter in `rider` that calls the gateway. No process, parsing, or caching code in `rider`.
- A test: same package as the code, under `src/test`; recorded output under `src/test/resources/fixtures/<command>/`.
