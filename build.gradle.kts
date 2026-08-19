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
            untilBuild = "262.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.Rider, riderVersion) {
                useInstaller = false
            }
        }
    }
}
