plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cessarrb.subtitulos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cessarrb.subtitulos"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
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
    // Traducción en el celular (gratis, offline)
    implementation("com.google.mlkit:translate:17.0.3")
    // Detecta en qué idioma está el texto
    implementation("com.google.mlkit:language-id:17.0.6")
}
