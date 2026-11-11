package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vcs.VcsException
import com.teamcomplex.plasticinsight.core.process.PlasticCancellation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlasticWorkspaceOperationsTest {
    @Test
    fun `groups paths per root and runs one call per root in first-seen order`() {
        val calls = ArrayList<Pair<Path, List<Path>>>()
        val firstInSecond = SECOND_ROOT.resolve("a.cs")
        val firstInFirst = FIRST_ROOT.resolve("b.cs")
        val secondInSecond = SECOND_ROOT.resolve("c.cs")

        val result = runPerRoot(
            targets = listOf(
                WorkspaceTarget(firstInSecond, SECOND_ROOT),
                WorkspaceTarget(firstInFirst, FIRST_ROOT),
                WorkspaceTarget(secondInSecond, SECOND_ROOT),
            ),
            cancellation = PlasticCancellation.NONE,
            operation = { root, paths, _ -> calls.add(root to paths.toList()) },
        )

        assertEquals(
            listOf(
                SECOND_ROOT to listOf(firstInSecond, secondInSecond),
                FIRST_ROOT to listOf(firstInFirst),
            ),
            calls,
        )
        assertEquals(setOf(firstInSecond, firstInFirst, secondInSecond), result.succeededPaths)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `a failing root reports its error while the other roots succeed`() {
        val failure = VcsException("synthetic failure")
        val failingPath = FIRST_ROOT.resolve("failing.cs")
        val succeedingPath = SECOND_ROOT.resolve("succeeding.cs")
        val calledRoots = ArrayList<Path>()

        val result = runPerRoot(
            targets = listOf(
                WorkspaceTarget(failingPath, FIRST_ROOT),
                WorkspaceTarget(succeedingPath, SECOND_ROOT),
            ),
            cancellation = PlasticCancellation.NONE,
            operation = { root, _, _ ->
                calledRoots.add(root)

                if (root == FIRST_ROOT) {
                    throw failure
                }
            },
        )

        assertEquals(listOf(FIRST_ROOT, SECOND_ROOT), calledRoots)
        assertEquals(setOf(succeedingPath), result.succeededPaths)
        assertEquals(1, result.errors.size)
        assertSame(failure, result.errors.single())
    }

    @Test
    fun `a target without a local path or mapped root is reported and never passed to the operation`() {
        val mappedPath = FIRST_ROOT.resolve("mapped.cs")
        val calls = ArrayList<Pair<Path, List<Path>>>()

        val result = runPerRoot(
            targets = listOf(
                WorkspaceTarget(path = null, root = FIRST_ROOT),
                WorkspaceTarget(path = FIRST_ROOT.resolve("unmapped.cs"), root = null),
                WorkspaceTarget(mappedPath, FIRST_ROOT),
            ),
            cancellation = PlasticCancellation.NONE,
            operation = { root, paths, _ -> calls.add(root to paths.toList()) },
        )

        assertEquals(listOf(FIRST_ROOT to listOf(mappedPath)), calls)
        assertEquals(setOf(mappedPath), result.succeededPaths)
        assertEquals(
            listOf(
                "Plastic workspace operation requires a local mapped path.",
                "Plastic workspace operation requires a local mapped path.",
            ),
            result.errors.map { error -> error.message },
        )
    }

    @Test
    fun `cancellation from the operation propagates and stops the remaining roots`() {
        val calledRoots = ArrayList<Path>()

        assertFailsWith<ProcessCanceledException> {
            runPerRoot(
                targets = listOf(
                    WorkspaceTarget(FIRST_ROOT.resolve("a.cs"), FIRST_ROOT),
                    WorkspaceTarget(SECOND_ROOT.resolve("b.cs"), SECOND_ROOT),
                ),
                cancellation = PlasticCancellation.NONE,
                operation = { root, _, _ ->
                    calledRoots.add(root)
                    throw ProcessCanceledException()
                },
            )
        }

        assertEquals(listOf(FIRST_ROOT), calledRoots)
    }

    @Test
    fun `cancellation from the progress check propagates before the operation runs`() {
        var operationCalls = 0

        assertFailsWith<ProcessCanceledException> {
            runPerRoot(
                targets = listOf(WorkspaceTarget(FIRST_ROOT.resolve("a.cs"), FIRST_ROOT)),
                cancellation = PlasticCancellation.NONE,
                operation = { _, _, _ -> operationCalls++ },
                checkCanceled = { throw ProcessCanceledException() },
            )
        }

        assertEquals(0, operationCalls)
    }

    @Test
    fun `progress is checked before each root`() {
        val events = ArrayList<String>()

        runPerRoot(
            targets = listOf(
                WorkspaceTarget(FIRST_ROOT.resolve("a.cs"), FIRST_ROOT),
                WorkspaceTarget(SECOND_ROOT.resolve("b.cs"), SECOND_ROOT),
            ),
            cancellation = PlasticCancellation.NONE,
            operation = { root, _, _ -> events.add("run ${root.fileName}") },
            checkCanceled = { events.add("check") },
        )

        assertEquals(listOf("check", "run first", "check", "run second"), events)
    }

    @Test
    fun `an empty target list runs nothing and returns an empty result`() {
        var operationCalls = 0
        var checks = 0

        val result = runPerRoot(
            targets = emptyList(),
            cancellation = PlasticCancellation.NONE,
            operation = { _, _, _ -> operationCalls++ },
            checkCanceled = { checks++ },
        )

        assertEquals(0, operationCalls)
        assertEquals(0, checks)
        assertTrue(result.succeededPaths.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `the cancellation object is passed to the operation unchanged`() {
        val cancellation = PlasticCancellation { false }
        val received = ArrayList<PlasticCancellation>()

        runPerRoot(
            targets = listOf(
                WorkspaceTarget(FIRST_ROOT.resolve("a.cs"), FIRST_ROOT),
                WorkspaceTarget(SECOND_ROOT.resolve("b.cs"), SECOND_ROOT),
            ),
            cancellation = cancellation,
            operation = { _, _, passed -> received.add(passed) },
        )

        assertEquals(2, received.size)
        received.forEach { passed -> assertSame(cancellation, passed) }
    }

    private companion object {
        val FIRST_ROOT: Path = Path.of("build", "tmp", "synthetic-workspaces", "first").toAbsolutePath().normalize()
        val SECOND_ROOT: Path = Path.of("build", "tmp", "synthetic-workspaces", "second").toAbsolutePath().normalize()
    }
}
