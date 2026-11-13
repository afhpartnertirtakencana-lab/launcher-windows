plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aquaboy.winlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aquaboy.winlauncher"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Hanya untuk FileProvider (membuka/membagikan berkas dari File Explorer)
    implementation("androidx.core:core:1.13.1")
}
