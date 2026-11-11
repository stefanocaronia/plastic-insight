# Plastic Insight

[![Build](https://github.com/stefanocaronia/plastic-insight/actions/workflows/build.yml/badge.svg)](https://github.com/stefanocaronia/plastic-insight/actions/workflows/build.yml)

Plastic Insight is a lightweight, inspector-first Rider integration for Unity Version Control / Plastic SCM. It brings the Plastic information needed while coding into Rider without trying to replace the Plastic GUI or become a complete Plastic client.

Plastic Insight is an independent, unofficial project. It is not sponsored by, affiliated with, endorsed by, or maintained by Unity Technologies or its affiliates, or by JetBrains s.r.o.

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
- JetBrains Rider 2026.2 or later (platform build 262+; validated on 2026.2.2, build `262.10315.191`, and 2026.3, build `263.7307.258`);
- an existing Plastic workspace mapped to **Plastic Insight** in Rider's Version Control settings.

This first release is Windows-only. Linux is not advertised as supported because executable discovery and the development packaging flow have not been validated there. PowerShell is required only when building from source, not when installing the ZIP.

`cm.exe` is resolved from `CM_EXE`, the standard Plastic installation folders, or `PATH`.

## Install

1. Download the latest `plastic-insight-<version>-signed.zip` from the GitHub Release (0.1.3 or later for Rider 2026.3; releases up to 0.1.3 ship an unsigned `plastic-insight-<version>.zip`).
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

Before tagging a release, maintainers run Plugin Verifier locally against the installed Rider SDK. Other Rider versions are opt-in, because each one downloads a full IDE:

```powershell
.\gradlew.bat verifyPlugin -PverifyRiderVersions=2026.2.2
```

Signing reads `chain.crt` and an unencrypted `private.pem` from the folder named by `PLUGIN_SIGNING_DIR` (or `-PpluginSigningDir=...`); without it, it falls back to `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD`. JetBrains Marketplace publishing reads `PUBLISH_TOKEN`. Nothing is stored in the repository, and `buildPlugin` works without any of them. Run `signPlugin` and `verifyPluginSignature` as separate Gradle invocations. A matching `v*` tag makes GitHub Actions rebuild the plugin, run the project tests and Gradle structure checks, sign the archive with the `CERTIFICATE_CHAIN` and `PRIVATE_KEY` repository secrets, verify the signature, and publish the signed ZIP as a GitHub Release asset; a tag without those secrets fails instead of releasing an unsigned archive. The workflow can also be started manually; it does not run on every push and does not retain multi-gigabyte Rider SDK caches.

## Documentation

- [Functional specification](Documentation/FunctionalSpecification.md)

## Unofficial project and trademarks

Plastic Insight is not sponsored by or affiliated with Unity Technologies or its affiliates. Unity, Unity Version Control, and Plastic SCM are trademarks or registered trademarks of Unity Technologies or its affiliates in the U.S. and elsewhere.

Plastic Insight is not affiliated with, endorsed by, sponsored by, or maintained by JetBrains s.r.o. JetBrains and JetBrains Rider are trademarks of JetBrains s.r.o. All product names and trademarks belong to their respective owners.

Plastic Insight uses its own original icon and branding; no Unity or JetBrains logo is included.

## Development transparency

This project was developed with assistance from AI coding tools. Every released change is reviewed, tested, and accepted by the project maintainers, who remain responsible for the software and its distribution.

## License

Plastic Insight is free and open-source software licensed under the [Apache License 2.0](LICENSE). It may be used, modified, and distributed, including commercially, subject to the license terms. The software is provided without warranties; see the license for the complete terms and limitations of liability.
