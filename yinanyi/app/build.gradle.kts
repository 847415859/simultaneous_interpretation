import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名：密钥库和密码放在不提交的 keystore.properties 里（见 README「发布」一节）。
// 没有这个文件时 release 包用 debug 签名，只能自己试用，不能和正式版互相覆盖安装。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// 阿里云 API Key 不再打进安装包，由用户在 App 设置里填写（或用 keys.properties 导入），
// 所以安装包可以公开发布。

android {
    namespace = "com.yinanyi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yinanyi.app"
        // 内部音频采集（AudioPlaybackCapture）从 Android 10 开始支持
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.0"
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

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 压缩和混淆：包更小，也更难被反编译；JNI 要用到的类在 proguard-rules.pro 里保护
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // sherpa-onnx 1.13.8，来自 https://github.com/k2-fsa/sherpa-onnx/releases
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
