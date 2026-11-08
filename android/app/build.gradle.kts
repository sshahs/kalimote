plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kalimote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kalimote.app"
        minSdk = 26
        targetSdk = 35
        // Releases pass -Pkalimote.versionName=1.2.3. The version code comes from it
        // (1.2.3 -> 10203), so APKs built by GitHub and Gitea upgrade each other.
        val release = providers.gradleProperty("kalimote.versionName").orNull
        versionCode = providers.gradleProperty("kalimote.versionCode").orNull?.toInt()
            ?: release?.let { v ->
                val (major, minor, patch) = (v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
                require(minor < 100 && patch < 100) { "Version $v: minor and patch must be below 100" }
                major * 10000 + minor * 100 + patch
            }
            ?: 1
        versionName = release ?: "0.1.0"
    }

    signingConfigs {
        // Release signing key from the environment (set as GitHub secrets).
        // Without it, release builds fall back to the debug key.
        val keystore = System.getenv("KALIMOTE_KEYSTORE_FILE")
        if (keystore != null && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KALIMOTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KALIMOTE_KEY_ALIAS")
                keyPassword = System.getenv("KALIMOTE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":atvremote"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
