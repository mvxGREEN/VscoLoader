plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.xxxgreen.mvx.downloader4vsco"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.xxxgreen.mvx.downloader4vsco"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "3.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // HTML Parsing (Replaces AngleSharp)
    implementation(libs.jsoup)

    // Firebase & Ads
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.firebase.crashlytics)

    // Billing
    implementation(libs.billing)

    // HTTP (Standard for Android)
    implementation(libs.okhttp)

    implementation(libs.glide)
    //kapt("com.github.bumptech.glide:compiler:4.16.0")
}