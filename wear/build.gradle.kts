plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}


/**
 * 大D 的版本号（手机、手表一样）：
 * - versionName = gradle.properties 的 dylanVersion ＋ 第几个 commit，例如 1.1.63（设定 →「關於與授權」看得到）；
 * - versionCode = commit 数，每次提交自动加一，不会再忘记更新。
 * 改大版本（1.1 → 1.2）只要改 gradle.properties。没有 git 时退回 1。
 */
val dylanBuild: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
}.getOrNull() ?: 1
val dylanVersion: String = (findProperty("dylanVersion") as String?) ?: "1.0"

android {
    namespace = "com.example.wristai.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 必须与手机 App 相同，Wear Data Layer 才会互通
        applicationId = "com.example.wristai"
        minSdk = 26
        targetSdk = 37
        versionCode = dylanBuild
        versionName = "$dylanVersion.$dylanBuild"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // 手錶專用元件：彎曲時間、隨圓形螢幕縮放的清單、貼底邊的主要按鈕、旋鈕捲動
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.input)   // 语音 / 键盘输入（报告书搜寻）
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}