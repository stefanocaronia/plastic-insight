package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs

class PlasticGatewayFactoryTest {
    @Test
    fun `creates a gateway after one successful executable resolution`() {
        val executable = Path.of("C:\\Tools\\Plastic\\cm.exe")
        val resolver = PlasticExecutableResolver(
            environment = emptyMap(),
            isRegularFile = { candidate -> candidate == executable },
        )
        val factory = PlasticGatewayFactory(
            executableResolver = resolver,
            runnerFactory = { PlasticProcessRunner { error("not invoked") } },
            workspaceLocator = PlasticWorkspaceLocator { false },
        )

        val result = assertIs<PlasticResult.Success<PlasticGateway>>(
            factory.create(PlasticRuntimeSettings(executableOverride = executable.toString())),
        )

        result.value.close()
    }

    @Test
    fun `maps an invalid authoritative executable without falling back`() {
        val factory = PlasticGatewayFactory(
            executableResolver = PlasticExecutableResolver(
                environment = mapOf("PATH" to "C:\\fallback"),
                isRegularFile = { false },
            ),
            workspaceLocator = PlasticWorkspaceLocator { false },
        )

        val result = assertIs<PlasticResult.Failure>(
            factory.create(PlasticRuntimeSettings(executableOverride = "C:\\missing\\cm.exe")),
        )

        assertIs<PlasticFailure.InvalidExecutableConfiguration>(result.reason)
    }

    @Test
    fun `rejects a historical lookup directory that does not exist`() {
        val executable = Path.of("C:\\Tools\\Plastic\\cm.exe")
        val missingDirectory = Files.createTempDirectory("plastic-gateway-factory-").resolve("missing")
        val factory = PlasticGatewayFactory(
            executableResolver = PlasticExecutableResolver(
                environment = emptyMap(),
                isRegularFile = { candidate -> candidate == executable },
            ),
            workspaceLocator = PlasticWorkspaceLocator { false },
        )

        val result = assertIs<PlasticResult.Failure>(
            factory.create(
                PlasticRuntimeSettings(
                    executableOverride = executable.toString(),
                    historicalLookupDirectory = missingDirectory,
                ),
            ),
        )

        assertIs<PlasticFailure.InvalidRuntimeConfiguration>(result.reason)
        Files.deleteIfExists(requireNotNull(missingDirectory.parent))
    }
}
