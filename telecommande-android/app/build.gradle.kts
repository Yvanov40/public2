plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.telecommande"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.telecommande"
        // API Bluetooth « HID device » (le téléphone joue le rôle d'une télécommande) : Android 9+
        minSdk = 28
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    // Signature : clé fournie par la CI si présente, sinon clé de debug
    val ksPath = System.getenv("TELECOMMANDE_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("perso") {
                storeFile = file(ksPath)
                storePassword = System.getenv("TELECOMMANDE_KEYSTORE_PASSWORD")
                keyAlias = "telecommande"
                keyPassword = System.getenv("TELECOMMANDE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("perso") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Aucune bibliothèque : uniquement le framework Android
dependencies {}
