plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 102
        versionName = "4.3.2"

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

    implementation(libs.okhttp)
    implementation(libs.glide)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}