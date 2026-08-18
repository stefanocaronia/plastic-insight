package com.teamcomplex.plasticinsight.core

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class PlasticExecutableSource {
    EXPLICIT_SETTING,
    CM_EXE,
    PROGRAM_FILES,
    PROGRAM_FILES_X86,
    PATH,
}

sealed interface PlasticExecutableResolution {
    data class Resolved(
        val executable: Path,
        val source: PlasticExecutableSource,
    ) : PlasticExecutableResolution

    data class InvalidOverride(
        val source: PlasticExecutableSource,
    ) : PlasticExecutableResolution

    data object NotFound : PlasticExecutableResolution
}

/** Resolves cm.exe once without scanning the filesystem recursively. */
class PlasticExecutableResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val isRegularFile: (Path) -> Boolean = { path -> Files.isRegularFile(path) },
    private val pathSeparator: Char = File.pathSeparatorChar,
) {
    fun resolve(settings: PlasticRuntimeSettings = PlasticRuntimeSettings()): PlasticExecutableResolution {
        authoritativeCandidate(
            rawValue = settings.executableOverride,
            source = PlasticExecutableSource.EXPLICIT_SETTING,
        )?.let { return it }

        authoritativeCandidate(
            rawValue = environmentValue(CM_EXE_ENVIRONMENT_VARIABLE),
            source = PlasticExecutableSource.CM_EXE,
        )?.let { return it }

        commonInstallation(
            environmentVariable = PROGRAM_FILES_ENVIRONMENT_VARIABLE,
            source = PlasticExecutableSource.PROGRAM_FILES,
        )?.let { return it }

        commonInstallation(
            environmentVariable = PROGRAM_FILES_X86_ENVIRONMENT_VARIABLE,
            source = PlasticExecutableSource.PROGRAM_FILES_X86,
        )?.let { return it }

        pathCandidate()?.let { return it }
        return PlasticExecutableResolution.NotFound
    }

    private fun authoritativeCandidate(
        rawValue: String?,
        source: PlasticExecutableSource,
    ): PlasticExecutableResolution? {
        if (rawValue.isNullOrBlank()) return null

        val candidate = parseAbsolutePath(rawValue)
        return if (candidate != null && regularFileExists(candidate)) {
            PlasticExecutableResolution.Resolved(candidate, source)
        } else {
            PlasticExecutableResolution.InvalidOverride(source)
        }
    }

    private fun commonInstallation(
        environmentVariable: String,
        source: PlasticExecutableSource,
    ): PlasticExecutableResolution.Resolved? {
        val baseDirectory = environmentValue(environmentVariable)
            ?.takeUnless(String::isBlank)
            ?.let(::parseAbsolutePath)
            ?: return null
        val candidate = baseDirectory.resolve(COMMON_RELATIVE_PATH).normalize()
        return candidate.takeIf(::regularFileExists)
            ?.let { PlasticExecutableResolution.Resolved(it, source) }
    }

    private fun pathCandidate(): PlasticExecutableResolution.Resolved? {
        val searchPath = environmentValue(PATH_ENVIRONMENT_VARIABLE)
            ?.takeUnless(String::isBlank)
            ?: return null

        for (rawDirectory in searchPath.split(pathSeparator)) {
            if (rawDirectory.isBlank()) continue
            val directory = parseAbsolutePath(rawDirectory) ?: continue
            val candidate = directory.resolve(EXECUTABLE_FILE_NAME).normalize()
            if (regularFileExists(candidate)) {
                return PlasticExecutableResolution.Resolved(candidate, PlasticExecutableSource.PATH)
            }
        }
        return null
    }

    private fun parseAbsolutePath(rawValue: String): Path? {
        val value = rawValue.trim().removeMatchingQuotes()
        if (value.isBlank()) return null
        return try {
            Path.of(value).takeIf(Path::isAbsolute)?.normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun regularFileExists(path: Path): Boolean =
        try {
            isRegularFile(path)
        } catch (_: SecurityException) {
            false
        }

    private fun environmentValue(name: String): String? =
        environment[name] ?: environment.entries.firstOrNull { (key) -> key.equals(name, ignoreCase = true) }?.value

    private fun String.removeMatchingQuotes(): String =
        if (length >= 2 && first() == '"' && last() == '"') substring(1, lastIndex).trim() else this

    private companion object {
        const val CM_EXE_ENVIRONMENT_VARIABLE = "CM_EXE"
        const val PROGRAM_FILES_ENVIRONMENT_VARIABLE = "ProgramFiles"
        const val PROGRAM_FILES_X86_ENVIRONMENT_VARIABLE = "ProgramFiles(x86)"
        const val PATH_ENVIRONMENT_VARIABLE = "PATH"
        const val EXECUTABLE_FILE_NAME = "cm.exe"
        val COMMON_RELATIVE_PATH: Path = Path.of("PlasticSCM5", "client", EXECUTABLE_FILE_NAME)
    }
}
