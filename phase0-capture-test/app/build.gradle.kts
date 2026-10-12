plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yinanyi.captest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yinanyi.captest"
        // 内部音频采集（AudioPlaybackCapture）从 Android 10 开始支持
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "0.0.3"
        ndk {
            // 只打包 64 位 ARM，安装包小一半
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // debug 包也用 Release 编译 llama.cpp，否则推理慢几十倍
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // sherpa-onnx 1.13.8，来自 https://github.com/k2-fsa/sherpa-onnx/releases
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
