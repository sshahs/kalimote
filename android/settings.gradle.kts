pluginManagement {
    repositories {
        google()
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
