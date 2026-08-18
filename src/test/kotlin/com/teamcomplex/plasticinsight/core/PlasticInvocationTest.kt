package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlasticInvocationTest {
    @Test
    fun `keeps every argument separate`() {
        val invocation = PlasticInvocation(
            executable = "cm.exe",
            arguments = listOf("cat", "Assets/file with spaces.cs#cs:42"),
            workingDirectory = Path.of("."),
            timeout = Duration.ofSeconds(3),
        )

        assertEquals(
            listOf("cm.exe", "cat", "Assets/file with spaces.cs#cs:42"),
            invocation.commandLine(),
        )
    }

    @Test
    fun `rejects a non-positive timeout`() {
        assertFailsWith<IllegalArgumentException> {
            PlasticInvocation(
                executable = "cm",
                arguments = emptyList(),
                workingDirectory = Path.of("."),
                timeout = Duration.ZERO,
            )
        }
    }

    @Test
    fun `rejects non-positive output capture limits`() {
        assertFailsWith<IllegalArgumentException> {
            PlasticInvocation(
                executable = "cm",
                arguments = emptyList(),
                workingDirectory = Path.of("."),
                timeout = Duration.ofSeconds(1),
                standardOutputLimitBytes = 0,
            )
        }
    }
}
