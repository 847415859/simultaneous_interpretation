import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 阿里云百炼 API Key 写在 local.properties（不提交）：dashscope.api.key=sk-xxx
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val dashscopeKey: String = localProps.getProperty("dashscope.api.key", "")

android {
    namespace = "com.yinanyi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yinanyi.app"
        // 内部音频采集（AudioPlaybackCapture）从 Android 10 开始支持
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"$dashscopeKey\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // debug 包也用 Release 编译 llama.cpp，否则推理慢几十倍
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
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
    buildFeatures {
        compose = true
        buildConfig = true
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // sherpa-onnx 1.13.8，来自 https://github.com/k2-fsa/sherpa-onnx/releases
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
