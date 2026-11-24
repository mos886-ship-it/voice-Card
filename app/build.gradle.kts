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
    namespace = "com.example.wristai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
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
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.anthropic.java)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 扫大D的主機的配对 QR 码（Google 扫码画面，不用相机权限）
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Google 云端硬碟备份：帐号授权（Identity AuthorizationClient）＋ Task.await
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    // 单元测试用真正的 org.json（Android 的版本在 JVM 测试里是空壳）
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}