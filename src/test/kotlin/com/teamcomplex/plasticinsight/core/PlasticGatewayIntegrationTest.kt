package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlasticGatewayIntegrationTest {
    @Test
    fun `runs the read-only gateway contract against an explicitly selected file`() {
        val configuredFile = System.getenv(TEST_FILE_ENVIRONMENT_VARIABLE)
        assumeTrue(
            "$TEST_FILE_ENVIRONMENT_VARIABLE is not configured; skipping the opt-in Plastic contract test.",
            !configuredFile.isNullOrBlank(),
        )
        val file = Path.of(configuredFile).toAbsolutePath().normalize()
        val gateway = assertIs<PlasticResult.Success<PlasticGateway>>(PlasticGatewayFactory().create()).value

        gateway.use {
            val lookup = assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(
                it.discoverWorkspace(requireNotNull(file.parent)),
            )
            val workspace = assertIs<PlasticWorkspaceLookup.Found>(lookup.value).workspace

            val status = assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(
                it.status(workspace, file, includePrivateFiles = true),
            )
            val workspaceStatus = assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(
                it.status(workspace, workspace.root, includePrivateFiles = true),
            )
            assertTrue(
                workspaceStatus.value.changes.all { change -> change.path.startsWith(workspace.root) },
                "The filtered workspace status returned a path outside the workspace.",
            )
            val baseline = assertIs<PlasticResult.Success<ByteArray>>(
                it.baseContent(workspace, status.value, file),
            )
            assertTrue(baseline.value.isNotEmpty(), "The selected baseline unexpectedly has no content.")

            val history = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
                it.fileHistory(workspace, file, PlasticHistoryRequest(limit = 10)),
            )
            val revision = history.value.revisions.firstOrNull { candidate ->
                candidate.entryKind == PlasticHistoryEntryKind.CONTENT_REVISION &&
                    candidate.changeset is PlasticHistoryChangeset.Number &&
                    candidate.dataStatus?.kind == PlasticRevisionDataStatusKind.AVAILABLE
            }
            assertTrue(revision != null, "The selected file has no available numeric revision.")

            val content = assertIs<PlasticResult.Success<ByteArray>>(it.revisionContent(revision))
            assertTrue(content.value.isNotEmpty(), "The selected revision unexpectedly has no content.")
        }
    }

    @Test
    fun `loads history for an explicitly selected pending move destination`() {
        val configuredFile = System.getenv(MOVED_TEST_FILE_ENVIRONMENT_VARIABLE)
        assumeTrue(
            "$MOVED_TEST_FILE_ENVIRONMENT_VARIABLE is not configured; skipping the opt-in pending-move test.",
            !configuredFile.isNullOrBlank(),
        )
        val file = Path.of(configuredFile).toAbsolutePath().normalize()
        val gateway = assertIs<PlasticResult.Success<PlasticGateway>>(PlasticGatewayFactory().create()).value

        gateway.use {
            val lookup = assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(
                it.discoverWorkspace(requireNotNull(file.parent)),
            )
            val workspace = assertIs<PlasticWorkspaceLookup.Found>(lookup.value).workspace
            val status = assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(
                it.status(workspace, workspace.root),
            )
            assertTrue(
                status.value.changes.any { change -> change.isMove && change.path.normalize() == file },
                "The selected file is not a pending move destination.",
            )

            val history = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
                it.fileHistory(workspace, file, PlasticHistoryRequest(limit = 10)),
            )
            assertTrue(history.value.revisions.isNotEmpty(), "The moved file unexpectedly has no history.")
        }
    }

    private companion object {
        const val TEST_FILE_ENVIRONMENT_VARIABLE = "PLASTIC_INSIGHT_TEST_FILE"
        const val MOVED_TEST_FILE_ENVIRONMENT_VARIABLE = "PLASTIC_INSIGHT_MOVED_TEST_FILE"
    }
}
