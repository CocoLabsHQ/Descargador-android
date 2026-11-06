plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.descargador.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.descargador.app"
        minSdk = 29
        targetSdk = 34
        // GitHub Actions pasa estos valores según la etiqueta (v0.3 -> versionName 0.3)
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
        versionName = (project.findProperty("versionName") as String?) ?: "0.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Firma estable: sin ella cada compilación tendría otra firma y Android rechazaría las actualizaciones.
    // Las credenciales llegan por variables de entorno (secretos de GitHub); nunca van en el código.
    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }

    // Equivale a extractNativeLibs=true: yt-dlp y ffmpeg se ejecutan desde librerías nativas
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
