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

        resConfigs("en", "zh", "es", "pt", "nb")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                abiFilters.add("arm64-v8a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName = "rtosify-app-${name}.apk"
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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.embedded)
    implementation(libs.gson)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu.core)
    implementation(libs.jsoup)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX
    val camerax_version = "1.3.0" // Or use a catalog managed version if preferred, but hardcoding for simplicity here as toml reading failed
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-video:${camerax_version}")
    implementation("androidx.camera:camera-extensions:${camerax_version}")

    // Health charts
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Google Tink for encryption
    implementation("com.google.crypto.tink:tink-android:1.12.0")

    // OSMDroid for open-source mapping (Find Device feature)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // WebRTC & WebSocket for Internet Transport
    implementation("io.github.webrtc-sdk:android:114.5735.02")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}