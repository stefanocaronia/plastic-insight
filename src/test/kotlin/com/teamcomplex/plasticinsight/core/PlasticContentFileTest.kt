package com.teamcomplex.plasticinsight.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlasticContentFileTest {
    @Test
    fun `lends a file that does not exist yet and removes it with its directory`() {
        lateinit var lent: Path

        val bytes = withTemporaryContentFile { file ->
            lent = file
            assertTrue(file.isAbsolute)
            assertFalse(Files.exists(file))
            Files.write(file, byteArrayOf(1, 2, 3))
            readBoundedContentFile(file, limitBytes = 16).bytes
        }

        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertFalse(Files.exists(lent))
        assertFalse(Files.exists(lent.parent))
    }

    @Test
    fun `removes the file when the command fails`() {
        lateinit var lent: Path

        assertFailsWith<IllegalStateException> {
            withTemporaryContentFile<Unit> { file ->
                lent = file
                Files.write(file, byteArrayOf(1))
                error("The command failed.")
            }
        }

        assertFalse(Files.exists(lent.parent))
    }

    @Test
    fun `reports a file over the limit as truncated without loading it`() {
        withTemporaryContentFile { file ->
            Files.write(file, ByteArray(17))

            val capture = readBoundedContentFile(file, limitBytes = 16)

            assertTrue(capture.truncated)
            assertEquals(17L, capture.totalBytesRead)
            assertEquals(0, capture.bytes.size)
        }
    }

    @Test
    fun `reads an empty revision as empty content`() {
        withTemporaryContentFile { file ->
            Files.write(file, byteArrayOf())

            val capture = readBoundedContentFile(file, limitBytes = 16)

            assertFalse(capture.truncated)
            assertEquals(0, capture.bytes.size)
        }
    }

    @Test
    fun `refuses a success that wrote no file`() {
        withTemporaryContentFile { file ->
            assertFailsWith<IllegalStateException> { readBoundedContentFile(file, limitBytes = 16) }
        }
    }
}
