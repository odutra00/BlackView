import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Senha inicial das câmeras lida de local.properties (não versionado):
//   blackview.cameraPassword=SUA_SENHA_NVR
// Usada apenas para pré-preencher a configuração na primeira execução.
// A senha pode ser alterada a qualquer momento na tela Ajustes da TV.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultCameraPassword: String = localProps.getProperty("blackview.cameraPassword", "")

android {
    namespace = "br.com.blackview.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.blackview.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        buildConfigField(
            "String",
            "DEFAULT_CAMERA_PASSWORD",
            "\"" + defaultCameraPassword.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        )

        ndk {
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("com.google.android.material:material:1.11.0")

    // LibVLC for Android (Native RTSP UDP, H.265 HW decode, G.711 A-law)
    implementation("org.videolan.android:libvlc-all:3.6.2")

    // Coroutines & Lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // JSON configuration persistence
    implementation("com.google.code.gson:gson:2.10.1")
}
