package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlasticCommandBuilderTest {
    @Test
    fun `workspace discovery preserves spaces and Unicode in a separate absolute argument`() {
        val workingDirectory = absoluteTestPath("runner")
        val targetPath = absoluteTestPath("Sample Workspace α", "nested project")
        val builder = PlasticCommandBuilder(
            executable = "C:\\Program Files\\PlasticSCM5\\client\\cm.exe",
            timeout = Duration.ofSeconds(7),
        )

        val invocation = builder.workspaceDiscovery(workingDirectory, targetPath)

        assertEquals(
            listOf(
                "C:\\Program Files\\PlasticSCM5\\client\\cm.exe",
                "getworkspacefrompath",
                targetPath.toString(),
                "--extended",
                "--format={wkname}\u001F{wkpath}\u001F{machine}\u001F{guid}\u001F{type}\u001F{dynamic}",
            ),
            invocation.commandLine(),
        )
        assertEquals(workingDirectory, invocation.workingDirectory)
        assertEquals(Duration.ofSeconds(7), invocation.timeout)
    }

    @Test
    fun `workspace discovery rejects a relative target`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.workspaceDiscovery(
                workingDirectory = absoluteTestPath("runner"),
                targetPath = Path.of("relative workspace"),
            )
        }
    }

    @Test
    fun `workspace discovery rejects a relative working directory`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.workspaceDiscovery(
                workingDirectory = Path.of("relative runner"),
                targetPath = absoluteTestPath("workspace"),
            )
        }
    }

    @Test
    fun `constrained status uses exact filtered contract for a Unicode scope`() {
        val workspaceRoot = absoluteTestPath("Sample Workspace")
        val scope = workspaceRoot.resolve("Source with spaces").resolve("Ω.cpp")
        val builder = PlasticCommandBuilder(executable = "cm.exe", timeout = Duration.ofSeconds(4))

        val invocation = builder.constrainedStatus(workspaceRoot, scope)

        assertEquals(
            listOf(
                "cm.exe",
                "status",
                scope.toString(),
                "--machinereadable",
                "--includeRevId",
                "--iscochanged",
                "--controlledchanged",
                "--changed",
                "--localdeleted",
                "--localmoved",
                "--fieldseparator=\u001F",
                "--startlineseparator=\u001E",
                "--endlineseparator=\u001D",
            ),
            invocation.commandLine(),
        )
        assertEquals(workspaceRoot, invocation.workingDirectory)
        assertEquals(Duration.ofSeconds(4), invocation.timeout)
    }

    @Test
    fun `workspace status uses the filtered root contract with a tighter output bound`() {
        val workspaceRoot = absoluteTestPath("Sample Workspace")
        val invocation = PlasticCommandBuilder(textOutputLimitBytes = 8 * 1024 * 1024)
            .workspaceStatus(workspaceRoot)

        assertEquals("status", invocation.arguments.first())
        assertEquals(workspaceRoot.toString(), invocation.arguments[1])
        assertEquals(workspaceRoot, invocation.workingDirectory)
        assertEquals(1024 * 1024, invocation.standardOutputLimitBytes)
        assertTrue("--controlledchanged" in invocation.arguments)
        assertTrue("--changed" in invocation.arguments)
        assertTrue("--localdeleted" in invocation.arguments)
        assertTrue("--localmoved" in invocation.arguments)
        assertTrue("--private" !in invocation.arguments)
    }

    @Test
    fun `workspace private discovery cuts ignored directory contents and remains output bounded`() {
        val workspaceRoot = absoluteTestPath("Sample Workspace")
        val invocation = PlasticCommandBuilder(textOutputLimitBytes = 8 * 1024 * 1024)
            .workspaceStatus(workspaceRoot, includePrivateFiles = true)

        assertEquals(1024 * 1024, invocation.standardOutputLimitBytes)
        assertTrue("--private" in invocation.arguments)
        assertTrue("--ignored" in invocation.arguments)
        assertTrue("--cutignored" in invocation.arguments)
    }

    @Test
    fun `constrained status can include private files only for its exact scope`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val file = workspaceRoot.resolve("new file.cs")

        val invocation = PlasticCommandBuilder().constrainedStatus(
            workspaceRoot = workspaceRoot,
            scope = file,
            includePrivateFiles = true,
        )

        assertTrue("--private" in invocation.arguments)
        assertTrue("--ignored" in invocation.arguments)
        assertTrue("--cutignored" in invocation.arguments)
        assertEquals(file.toString(), invocation.arguments[1])
    }

    @Test
    fun `constrained status rejects a relative workspace root`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.constrainedStatus(
                workspaceRoot = Path.of("relative workspace"),
                scope = absoluteTestPath("workspace", "file.txt"),
            )
        }
    }

    @Test
    fun `constrained status rejects a relative scope`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.constrainedStatus(
                workspaceRoot = absoluteTestPath("workspace"),
                scope = Path.of("relative scope"),
            )
        }
    }

    @Test
    fun `constrained status rejects an unbounded workspace-root scope`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.constrainedStatus(
                workspaceRoot = workspaceRoot,
                scope = workspaceRoot,
            )
        }
    }

    @Test
    fun `constrained status rejects an absolute scope outside the workspace`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.constrainedStatus(
                workspaceRoot = absoluteTestPath("workspace"),
                scope = absoluteTestPath("other workspace", "file.txt"),
            )
        }
    }

    @Test
    fun `workspace baseline uses changeset spec and raw byte contract`() {
        val workspaceRoot = absoluteTestPath("Sample Workspace")
        val basePath = workspaceRoot.resolve("Source with spaces").resolve("Ω.cpp")
        val builder = PlasticCommandBuilder(
            executable = "cm.exe",
            binaryOutputLimitBytes = 4096,
            errorOutputLimitBytes = 512,
        )

        val invocation = builder.workspaceBaseline(workspaceRoot, basePath, workspaceChangeset = 42)

        assertEquals(
            listOf("cm.exe", "cat", "$basePath#cs:42", "--raw"),
            invocation.commandLine(),
        )
        assertEquals(workspaceRoot, invocation.workingDirectory)
        assertEquals(4096, invocation.standardOutputLimitBytes)
        assertEquals(512, invocation.standardErrorLimitBytes)
    }

    @Test
    fun `workspace baseline rejects a path outside the workspace`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.workspaceBaseline(
                workspaceRoot = absoluteTestPath("workspace"),
                basePath = absoluteTestPath("other workspace", "file.txt"),
                workspaceChangeset = 42,
            )
        }
    }

    @Test
    fun `workspace baseline rejects a negative changeset`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.workspaceBaseline(
                workspaceRoot = workspaceRoot,
                basePath = workspaceRoot.resolve("file.txt"),
                workspaceChangeset = -1,
            )
        }
    }

    @Test
    fun `add uses explicit ordered paths without recursive expansion`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val directory = workspaceRoot.resolve("new directory")
        val file = directory.resolve("Ω.cs")

        val invocation = PlasticCommandBuilder(executable = "cm.exe").add(
            workspaceRoot = workspaceRoot,
            paths = listOf(file, directory, file),
        )

        assertEquals(
            listOf("cm.exe", "add", "--noinfo", directory.toString(), file.toString()),
            invocation.commandLine(),
        )
        assertEquals(workspaceRoot, invocation.workingDirectory)
        assertEquals(1024 * 1024, invocation.standardOutputLimitBytes)
    }

    @Test
    fun `undo is exact and rejects workspace root or outside paths`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val file = workspaceRoot.resolve("changed file.cs")
        val builder = PlasticCommandBuilder(executable = "cm.exe")

        val invocation = builder.undo(workspaceRoot, listOf(file))

        assertEquals(listOf("cm.exe", "undo", "--silent", file.toString()), invocation.commandLine())
        assertTrue("-r" !in invocation.arguments)
        assertTrue("--recursive" !in invocation.arguments)
        assertFailsWith<IllegalArgumentException> { builder.undo(workspaceRoot, listOf(workspaceRoot)) }
        assertFailsWith<IllegalArgumentException> {
            builder.undo(workspaceRoot, listOf(absoluteTestPath("outside", "file.cs")))
        }
    }

    @Test
    fun `workspace mutations reject empty and relative paths`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> { builder.add(workspaceRoot, emptyList()) }
        assertFailsWith<IllegalArgumentException> { builder.add(workspaceRoot, listOf(Path.of("relative.cs"))) }
    }

    @Test
    fun `file history uses bounded XML contract`() {
        val workspaceRoot = absoluteTestPath("Sample Workspace")
        val filePath = workspaceRoot.resolve("Source with spaces").resolve("Ω.cpp")
        val builder = PlasticCommandBuilder(executable = "cm.exe")

        val invocation = builder.fileHistory(workspaceRoot, filePath, limit = 100)

        assertEquals(
            listOf(
                "cm.exe",
                "history",
                filePath.toString(),
                "--xml",
                "--encoding=utf-8",
                "--moveddeleted",
                "--limit=100",
            ),
            invocation.commandLine(),
        )
        assertEquals(workspaceRoot, invocation.workingDirectory)
    }

    @Test
    fun `file history rejects an unbounded limit`() {
        val workspaceRoot = absoluteTestPath("workspace")
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.fileHistory(workspaceRoot, workspaceRoot.resolve("file.txt"), limit = 1001)
        }
    }

    @Test
    fun `historical path resolution keeps the query in one argument`() {
        val executionDirectory = absoluteTestPath("outside workspace")
        val builder = PlasticCommandBuilder(executable = "cm.exe")

        val invocation = builder.historicalPathResolution(
            executionDirectory = executionDirectory,
            itemId = 123,
            changeset = 42,
            repository = "Repository with spaces",
            server = "server.example.com:8087",
        )

        assertEquals(
            listOf(
                "cm.exe",
                "find",
                "revision",
                "where itemid=123 and changeset=42 on repository 'Repository with spaces@server.example.com:8087'",
                "--xml",
                "--encoding=utf-8",
                "--nototal",
            ),
            invocation.commandLine(),
        )
        assertEquals(executionDirectory, invocation.workingDirectory)
    }

    @Test
    fun `historical path resolution rejects query control characters`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.historicalPathResolution(
                executionDirectory = absoluteTestPath("outside workspace"),
                itemId = 123,
                changeset = 42,
                repository = "repo' or itemid>0",
                server = "server",
            )
        }
    }

    @Test
    fun `historical path resolution rejects an item ID outside the server query range`() {
        val builder = PlasticCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.historicalPathResolution(
                executionDirectory = absoluteTestPath("outside workspace"),
                itemId = Int.MAX_VALUE.toLong() + 1,
                changeset = 42,
                repository = "repository",
                server = "server",
            )
        }
    }

    @Test
    fun `historical content uses server path revision spec and raw bytes`() {
        val executionDirectory = absoluteTestPath("outside workspace")
        val builder = PlasticCommandBuilder(
            executable = "cm.exe",
            binaryOutputLimitBytes = 8192,
        )

        val invocation = builder.historicalContent(
            executionDirectory = executionDirectory,
            historicalPath = "/Source with spaces/Ω.cpp",
            changeset = 42,
            repository = "Repository",
            server = "server.example.com:8087",
        )

        assertEquals(
            listOf(
                "cm.exe",
                "cat",
                "serverpath:/Source with spaces/Ω.cpp#cs:42@Repository@server.example.com:8087",
                "--raw",
            ),
            invocation.commandLine(),
        )
        assertEquals(8192, invocation.standardOutputLimitBytes)
    }

    private fun absoluteTestPath(vararg parts: String): Path =
        parts.fold(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) { path, part ->
            path.resolve(part)
        }
}
