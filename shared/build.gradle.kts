plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.voiceai.shared"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Wear Data Layer（MessageClient / ChannelClient / CapabilityClient），手机与手表都需要
    api(libs.play.services.wear)
    // Task.await()
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.play.services)
}
