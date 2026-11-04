plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tapper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tapper.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersion") as String?) ?: "1.0"
    }
    // CI signs every build with the private key held in GitHub secrets (see .github/workflows),
    // so each build installs over the previous one and the in-app updater works. Without those
    // environment variables (local builds) Android's default debug key is used.
    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_FILE")
        if (keystorePath != null) {
            getByName("debug") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.13.1") // FileProvider, for installing downloaded updates
}
