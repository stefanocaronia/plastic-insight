import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val riderVersion = "2026.2"
val configuredRiderHome = providers.gradleProperty("riderHome")
    .orElse(providers.environmentVariable("RIDER_HOME"))
    .orNull
val defaultRiderHome = providers.environmentVariable("LOCALAPPDATA")
    .orNull
    ?.let { file("$it/Programs/Rider") }
    ?.takeIf { it.isDirectory }
val isCi = providers.environmentVariable("CI")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
    .get()
val localRiderHome = if (isCi) null else configuredRiderHome?.let(::file) ?: defaultRiderHome

// Rider 2026.3 (263) moved the VCS API out of the platform into the bundled
// "Version Control" plugin; on 262 the same classes are part of the platform.
// At runtime both resolve through <depends>com.intellij.modules.vcs</depends>.
val vcsPluginBaseline = 263
val downloadedRiderBaseline = 262
val riderBaseline = localRiderHome
    ?.let { providers.fileContents(layout.projectDirectory.file(it.resolve("build.txt").absolutePath)).asText.orNull }
    ?.substringAfter('-')
    ?.substringBefore('.')
    ?.trim()
    ?.toIntOrNull()
    ?: downloadedRiderBaseline
val extraVerifiedRiderVersions = providers.gradleProperty("verifyRiderVersions")
    .orNull
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    .orEmpty()

dependencies {
    intellijPlatform {
        if (localRiderHome != null) {
            local(localRiderHome)
        } else {
            rider(riderVersion) {
                useInstaller = false
            }
            jetbrainsRuntime()
        }
        if (riderBaseline >= vcsPluginBaseline) {
            bundledPlugin("com.intellij.platform.vcs")
            bundledModule("intellij.platform.vcs.core")
        }
        zipSigner()
    }

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.processResources {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }

    // Marketplace credentials come from the environment and are never stored in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            if (localRiderHome != null) {
                local(localRiderHome)
            } else {
                create(IntelliJPlatformType.Rider, riderVersion) {
                    useInstaller = false
                }
            }
            // Opt-in, because each version downloads a full Rider: -PverifyRiderVersions=2026.2.2
            extraVerifiedRiderVersions.forEach { version ->
                create(IntelliJPlatformType.Rider, version) {
                    useInstaller = false
                }
            }
        }
    }
}
