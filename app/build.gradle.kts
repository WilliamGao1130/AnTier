plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 稳定签名：从环境变量读取 keystore 信息。
 * CI 中由 GitHub Secrets 注入（ANTHER_KEYSTORE_BASE64 解码后给出文件路径），
 * 本地可用 export ANTHER_KEYSTORE_FILE=... 等变量复用同一把密钥。
 * 未配置时回退为：debug 用本机 debug 密钥，release 不签名（与原行为一致）。
 */
val signingConfigured = listOf(
    "ANTHER_KEYSTORE_FILE",
    "ANTHER_KEYSTORE_PASSWORD",
    "ANTHER_KEY_ALIAS",
    "ANTHER_KEY_PASSWORD"
).all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.antier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.antier"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.2.1"
    }

    signingConfigs {
        create("release") {
            if (signingConfigured) {
                storeFile = file(System.getenv("ANTHER_KEYSTORE_FILE")!!)
                storePassword = System.getenv("ANTHER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANTHER_KEY_ALIAS")
                keyPassword = System.getenv("ANTHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // CI 配置了稳定签名时，debug 包也使用同一把密钥，
            // 保证测试包与发布包可互相覆盖安装。
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        aidl = true
        buildConfig = true
    }
}

dependencies {
    // JNI 内核由 easytier-jni 仓库独立构建发布（v2.6.4 稳定 API）
    implementation("com.easytier:easytier-jni:2.6.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
