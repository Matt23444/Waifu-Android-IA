plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.waifu.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.waifu.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Solo 64 bits: cubre prácticamente todos los teléfonos actuales y reduce el tamaño del APK
        ndk {
            abiFilters += listOf("arm64-v8a")
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
    // Cerebro: llama.cpp para Android (modelos GGUF)
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Para descomprimir los modelos .tar.bz2 dentro del teléfono
    implementation("org.apache.commons:commons-compress:1.26.2")
}
