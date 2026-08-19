package com.teamcomplex.plasticinsight.rider

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlasticRootCheckerTest {
    private lateinit var temporaryRoot: Path

    @BeforeTest
    fun createTemporaryRoot() {
        val testOutput = Path.of("build", "tmp", "plastic-root-checker").toAbsolutePath()
        Files.createDirectories(testOutput)
        temporaryRoot = Files.createTempDirectory(testOutput, "workspace-")
    }

    @AfterTest
    fun deleteTemporaryRoot() {
        check(temporaryRoot.toFile().deleteRecursively())
    }

    @Test
    fun `recognizes only the exact directory containing both Plastic markers`() {
        val administrativeDirectory = Files.createDirectory(temporaryRoot.resolve(".plastic"))
        Files.writeString(administrativeDirectory.resolve("plastic.workspace"), "synthetic")
        val child = Files.createDirectory(temporaryRoot.resolve("Source"))

        assertTrue(PlasticRootChecker.isRootPath(temporaryRoot))
        assertFalse(PlasticRootChecker.isRootPath(child))
    }

    @Test
    fun `rejects an administrative directory without the workspace marker`() {
        Files.createDirectory(temporaryRoot.resolve(".plastic"))

        assertFalse(PlasticRootChecker.isRootPath(temporaryRoot))
    }

    @Test
    fun `administrative path is scoped to the discovered workspace root`() {
        val source = temporaryRoot.resolve("Source").resolve("file.cs")

        assertTrue(
            PlasticRootChecker.isAdministrativePath(
                temporaryRoot,
                temporaryRoot.resolve(".plastic").resolve("plastic.workspace"),
            ),
        )
        assertFalse(PlasticRootChecker.isAdministrativePath(temporaryRoot, source))
    }
}
