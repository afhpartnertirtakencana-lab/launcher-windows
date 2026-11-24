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
        versionCode = 44
        versionName = "2.15.0"
        // Alamat repositori ("pemilik/nama") untuk pemeriksa pembaruan; diisi otomatis oleh GitHub Actions.
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Kunci tetap supaya setiap build bertanda tangan sama dan bisa dipasang sebagai pembaruan.
    signingConfigs {
        getByName("debug") {
            storeFile = file("winlauncher.keystore")
            storePassword = "winlauncher"
            keyAlias = "winlauncher"
            keyPassword = "winlauncher"
        }
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
