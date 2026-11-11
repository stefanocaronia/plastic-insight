---
title: Releasing Plastic Insight
status: Active
created: 2026-11-11
updated: 2026-11-11
---

# Releasing Plastic Insight

How a version goes from the working tree to a signed GitHub Release and to JetBrains Marketplace. Everything here is public; the signing key, its certificate and the Marketplace token are never stored in the repository.

## What a release is

- `version` in `gradle.properties` and a matching entry in `<change-notes>` of `src/main/resources/META-INF/plugin.xml`;
- an annotated git tag `v<version>` on `main`;
- a GitHub Release carrying `plastic-insight-<version>-signed.zip`, built and signed by GitHub Actions;
- the same version on JetBrains Marketplace, uploaded by the maintainer.

## One-time setup

### Signing key and certificate

Marketplace expects archives signed by the author. The key and a self-signed certificate were generated once with OpenSSL, following the JetBrains plugin signing guide:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
```

```bash
openssl rsa -in private_encrypted.pem -out private.pem
```

```bash
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Keep `chain.crt` and the unencrypted `private.pem` in a folder outside the repository and delete `private_encrypted.pem`. Back the folder up: updates should keep being signed with the same key.

The certificate lasts 365 days (the current one expires on 2027-11-11). To renew it, run the third command again with the same `private.pem`, then replace the local `chain.crt` and the `CERTIFICATE_CHAIN` secret.

### Local signing

Point the build at that folder with the `PLUGIN_SIGNING_DIR` environment variable or `-PpluginSigningDir=<folder>`. Without it the build falls back to the `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` environment variables, which is what GitHub Actions uses.

### GitHub Actions secrets

The release workflow signs with two repository secrets holding the content of the two files. From Git Bash, in the repository folder:

```bash
gh secret set CERTIFICATE_CHAIN < <folder>/chain.crt
```

```bash
gh secret set PRIVATE_KEY < <folder>/private.pem
```

PowerShell has no `<` redirection; use Git Bash, or paste the values in **Settings | Secrets and variables | Actions**. The workflow runs only on `v*` tags and manual dispatch, so the secrets are not exposed to pull requests.

### JetBrains Marketplace

The maintainer owns the vendor profile and accepts the Marketplace agreements. The first version of a plugin is uploaded by hand at <https://plugins.jetbrains.com/author/me> with **Add new plugin**, and JetBrains reviews it before it is listed; 0.1.4 was submitted this way on 2026-11-11.

## Releasing a version

1. Set `version` in `gradle.properties` and add the entry at the top of `<change-notes>` in `plugin.xml`. Update `README.md` and the functional specification when behavior or compatibility changed.
2. Test and package against the locally installed Rider:

   ```powershell
   .\gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
   ```

3. Run Plugin Verifier against the installed Rider and the oldest supported one. Each extra version downloads a full IDE, so this stays a local gate and is not part of CI:

   ```powershell
   .\gradlew.bat verifyPlugin -PverifyRiderVersions=2026.2.2
   ```

4. Commit and push `main`.
5. Optional but cheap: start the workflow by hand and check that the **Sign and verify signature** step passes before tagging.

   ```bash
   gh workflow run build.yml --ref main
   ```

6. Tag and push the tag:

   ```bash
   git tag -a v<version> -m "Plastic Insight <version>"
   ```

   ```bash
   git push origin v<version>
   ```

   The workflow checks that the tag matches `gradle.properties`, rebuilds against a downloaded Rider 2026.2, runs the tests and structure checks, signs the archive, verifies the signature and creates the GitHub Release with the signed ZIP only.
7. Upload the signed ZIP from the GitHub Release to Marketplace: open the plugin page as its author and use **Upload Update**. JetBrains reviews updates too.

To sign locally instead, for example to upload before tagging, run the two tasks as separate Gradle invocations and take `build\distributions\plastic-insight-<version>-signed.zip`:

```powershell
.\gradlew.bat signPlugin
```

```powershell
.\gradlew.bat verifyPluginSignature
```

## Things that go wrong

- **`signPlugin verifyPluginSignature` in one command fails.** Run them one after the other.
- **A tag does not release.** The tag must equal `v` + `version`; a mismatch fails the **Validate release tag** step. A tag also fails when the two signing secrets are missing, so an unsigned archive is never released. Fix the cause, delete the tag locally and on GitHub, and tag again.
- **`build.ps1` ignores `-P` options.** Call `gradlew.bat` directly for Plugin Verifier and local signing.
- **CI and the local build use different Rider versions.** CI compiles against the downloaded Rider 2026.2 SDK, the local build against the installed Rider. The 263-only VCS dependencies in `build.gradle.kts` are added from the SDK's own build number, so both work; a compile error that appears in only one of them usually means an API moved between the two.
- **A manual workflow run without secrets** still succeeds, but its artifact is unsigned.

## Not automated yet

Marketplace updates are still uploaded by hand. `build.gradle.kts` already reads `PUBLISH_TOKEN` for `publishPlugin`; once the plugin is approved, a Marketplace token stored as a repository secret and a `publishPlugin` step in the workflow would publish each tag there as well.
