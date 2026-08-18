package com.teamcomplex.plasticinsight.core

import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PlasticExecutableResolverTest {
    @Test
    fun `explicit setting wins over every automatic source`() {
        val explicit = absoluteTestPath("configured", "cm.exe")
        val environmentExecutable = absoluteTestPath("environment", "cm.exe")
        val programFiles = absoluteTestPath("Program Files")
        val pathDirectory = absoluteTestPath("path")
        val resolver = resolver(
            existing = setOf(
                explicit,
                environmentExecutable,
                commonExecutable(programFiles),
                pathDirectory.resolve("cm.exe"),
            ),
            environment = mapOf(
                "CM_EXE" to environmentExecutable.toString(),
                "ProgramFiles" to programFiles.toString(),
                "PATH" to pathDirectory.toString(),
            ),
        )

        val result = resolver.resolve(PlasticRuntimeSettings(executableOverride = explicit.toString()))

        assertEquals(
            PlasticExecutableResolution.Resolved(explicit, PlasticExecutableSource.EXPLICIT_SETTING),
            result,
        )
    }

    @Test
    fun `invalid explicit setting is authoritative`() {
        val environmentExecutable = absoluteTestPath("environment", "cm.exe")
        val resolver = resolver(
            existing = setOf(environmentExecutable),
            environment = mapOf("CM_EXE" to environmentExecutable.toString()),
        )

        val result = resolver.resolve(
            PlasticRuntimeSettings(executableOverride = absoluteTestPath("missing", "cm.exe").toString()),
        )

        assertEquals(
            PlasticExecutableResolution.InvalidOverride(PlasticExecutableSource.EXPLICIT_SETTING),
            result,
        )
    }

    @Test
    fun `invalid CM_EXE is authoritative over common locations`() {
        val programFiles = absoluteTestPath("Program Files")
        val resolver = resolver(
            existing = setOf(commonExecutable(programFiles)),
            environment = mapOf(
                "CM_EXE" to absoluteTestPath("missing", "cm.exe").toString(),
                "ProgramFiles" to programFiles.toString(),
            ),
        )

        val result = resolver.resolve()

        assertEquals(
            PlasticExecutableResolution.InvalidOverride(PlasticExecutableSource.CM_EXE),
            result,
        )
    }

    @Test
    fun `common Program Files location precedes x86 and PATH`() {
        val programFiles = absoluteTestPath("Program Files")
        val programFilesX86 = absoluteTestPath("Program Files x86")
        val pathDirectory = absoluteTestPath("path")
        val expected = commonExecutable(programFiles)
        val resolver = resolver(
            existing = setOf(
                expected,
                commonExecutable(programFilesX86),
                pathDirectory.resolve("cm.exe"),
            ),
            environment = mapOf(
                "ProgramFiles" to programFiles.toString(),
                "ProgramFiles(x86)" to programFilesX86.toString(),
                "PATH" to pathDirectory.toString(),
            ),
        )

        val result = resolver.resolve()

        assertEquals(
            PlasticExecutableResolution.Resolved(expected, PlasticExecutableSource.PROGRAM_FILES),
            result,
        )
    }

    @Test
    fun `x86 common location is used before PATH`() {
        val programFilesX86 = absoluteTestPath("Program Files x86")
        val pathDirectory = absoluteTestPath("path")
        val expected = commonExecutable(programFilesX86)
        val resolver = resolver(
            existing = setOf(expected, pathDirectory.resolve("cm.exe")),
            environment = mapOf(
                "ProgramFiles(x86)" to programFilesX86.toString(),
                "PATH" to pathDirectory.toString(),
            ),
        )

        val result = resolver.resolve()

        assertEquals(
            PlasticExecutableResolution.Resolved(expected, PlasticExecutableSource.PROGRAM_FILES_X86),
            result,
        )
    }

    @Test
    fun `PATH lookup preserves spaces Unicode and directory order`() {
        val missingDirectory = absoluteTestPath("missing path")
        val expectedDirectory = absoluteTestPath("工具 path")
        val expected = expectedDirectory.resolve("cm.exe")
        val searchPath = listOf(missingDirectory, expectedDirectory).joinToString(File.pathSeparator)
        val resolver = resolver(
            existing = setOf(expected),
            environment = mapOf("Path" to searchPath),
        )

        val result = resolver.resolve()

        assertEquals(
            PlasticExecutableResolution.Resolved(expected, PlasticExecutableSource.PATH),
            result,
        )
    }

    @Test
    fun `missing executable returns a typed result`() {
        val result = resolver(existing = emptySet(), environment = emptyMap()).resolve()

        assertIs<PlasticExecutableResolution.NotFound>(result)
    }

    @Test
    fun `runtime settings reject a non-positive timeout`() {
        assertFailsWith<IllegalArgumentException> {
            PlasticRuntimeSettings(commandTimeout = Duration.ZERO)
        }
    }

    private fun resolver(
        existing: Set<Path>,
        environment: Map<String, String>,
    ): PlasticExecutableResolver =
        PlasticExecutableResolver(
            environment = environment,
            isRegularFile = { path -> path.normalize() in existing },
        )

    private fun commonExecutable(programFiles: Path): Path =
        programFiles.resolve("PlasticSCM5").resolve("client").resolve("cm.exe")

    private fun absoluteTestPath(vararg parts: String): Path =
        parts.fold(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) { path, part ->
            path.resolve(part)
        }
}
