plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.extensions"

    buildFeatures {
        resValues = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.jsoup)
    implementation(libs.injekt.core)
}
