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

            assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(it.status(workspace, file))
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

    private companion object {
        const val TEST_FILE_ENVIRONMENT_VARIABLE = "PLASTIC_INSIGHT_TEST_FILE"
    }
}
