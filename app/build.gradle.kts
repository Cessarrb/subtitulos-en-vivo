plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cessarrb.subtitulos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cessarrb.subtitulos"
        minSdk = 29          // Android 10: lo mínimo para capturar el audio interno
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Llave fija para que cada APK nuevo se instale encima del anterior
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Reconocimiento de voz en el celular
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Traducción en el celular (gratis, offline)
    implementation("com.google.mlkit:translate:17.0.3")
}
