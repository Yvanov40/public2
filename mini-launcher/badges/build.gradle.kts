plugins {
    id("com.android.application")
}

// Petite appli séparée « Pastilles » : elle seule a l'accès aux notifications et transmet au
// launcher le nombre de notifications par appli. Si son installation est refusée, le
// launcher n'est pas touché.
android {
    namespace = "fr.minilauncher.badges"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.minilauncher.badges"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
