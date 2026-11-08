// Optional Maven Central mirror (e.g. Google's), set by CI runners that hit
// Maven Central's per-IP rate limits (HTTP 429).
val mavenMirror: String? = System.getenv("KALIMOTE_MAVEN_MIRROR")?.takeIf { it.isNotBlank() }

pluginManagement {
    repositories {
        google()
        System.getenv("KALIMOTE_MAVEN_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenMirror?.let { maven(it) }
        mavenCentral()
    }
}

rootProject.name = "kalimote"

// The protocol library is plain Kotlin/JVM and can be built and tested
// without the Android SDK: ./gradlew -Pkalimote.jvmOnly=true :atvremote:test
include(":atvremote")
if (providers.gradleProperty("kalimote.jvmOnly").orNull != "true") {
    include(":app")
}
