plugins {
    id("com.android.library")
    id("kotlin-android")
    // 如果不再需要发布，可以不引入 'maven-publish'
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "eu.kanade.tachiyomi.extensions"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.jsoup)
    implementation(libs.injekt.core)
}
