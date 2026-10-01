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
        // CI passes these for releases: -Pkalimote.versionName=1.2.3 -Pkalimote.versionCode=42
        versionCode = providers.gradleProperty("kalimote.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("kalimote.versionName").orNull ?: "0.1.0"
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
