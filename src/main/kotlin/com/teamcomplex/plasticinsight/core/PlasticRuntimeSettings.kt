package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.time.Duration

/** Runtime values that can later be backed by Rider settings. */
data class PlasticRuntimeSettings(
    val executableOverride: String? = null,
    val commandTimeout: Duration = Duration.ofSeconds(15),
    val historicalLookupDirectory: Path = defaultHistoricalLookupDirectory(),
) {
    init {
        require(!commandTimeout.isNegative && !commandTimeout.isZero) {
            "The Plastic command timeout must be positive."
        }
        require(historicalLookupDirectory.isAbsolute) {
            "The historical lookup directory must be absolute."
        }
    }

    companion object {
        private fun defaultHistoricalLookupDirectory(): Path =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    }
}
