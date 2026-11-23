plugins {
    id("com.android.application")
}

android {
    namespace = "fr.minilauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.minilauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "4.4"
    }

    // Clé fixe versionnée : chaque nouvel APK s'installe par-dessus le précédent.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
