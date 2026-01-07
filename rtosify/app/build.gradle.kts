plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ailife.rtosify"
    compileSdk = 36
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    defaultConfig {
        applicationId = "com.ailife.rtosify"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.embedded)
    implementation(libs.gson)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu.core)
    implementation(libs.jsoup)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX
    val camerax_version = "1.3.0" // Or use a catalog managed version if preferred, but hardcoding for simplicity here as toml reading failed
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-extensions:${camerax_version}")

    // Health charts
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}