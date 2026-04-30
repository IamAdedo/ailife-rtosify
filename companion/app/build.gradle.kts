plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.iamadedo.watchapp"
    compileSdk = 36
    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }
    defaultConfig {
        applicationId = "com.iamadedo.watchapp"
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
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName = "companion-app-${name}.apk"
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.gson)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Google Tink for encryption
    implementation("com.google.crypto.tink:tink-android:1.12.0")

    // OSMDroid for open-source mapping (Find Device feature)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // WebRTC & WebSocket for Internet Transport
    implementation("io.github.webrtc-sdk:android:114.5735.02")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material3:material3-android:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("com.github.woheller69:FreeDroidWarn:V1.+")
}
