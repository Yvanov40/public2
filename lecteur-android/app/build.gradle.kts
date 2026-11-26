plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version de NewPipeExtractor : la CI passe la dernière publiée (-PnpeVersion=…)
val npeVersion = (project.findProperty("npeVersion") as String?)?.takeIf { it.isNotBlank() } ?: "v0.24.8"
val media3 = "1.5.1"

android {
    namespace = "fr.lecteur"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.lecteur"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
        buildConfigField("String", "NPE_VERSION", "\"$npeVersion\"")
    }

    // Signature : clé fournie par la CI (secret GitHub) si présente, sinon clé de debug
    val ksPath = System.getenv("LECTEUR_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("perso") {
                storeFile = file(ksPath)
                storePassword = System.getenv("LECTEUR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LECTEUR_KEY_ALIAS") ?: "lecteur"
                keyPassword = System.getenv("LECTEUR_KEYSTORE_PASSWORD")
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.md",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:$npeVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Chromecast / enceintes Google Cast
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
