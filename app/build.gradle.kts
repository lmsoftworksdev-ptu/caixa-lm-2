plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.lmsoftworks.caixa2"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.lmsoftworks.caixa2"
        minSdk = 23
        targetSdk = 23
        // o GitHub passa o número da versão (-PversionCode=N) ao compilar; no computador fica 1
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.2"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Player (áudio e vídeo juntados do YouTube)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")

    // Busca e extração de streams do YouTube
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
