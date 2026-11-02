plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.neocities.esfores.funpostfm"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.neocities.esfores.funpostfm"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Release signing comes from the environment (GitHub secrets), never from the repo.
    val ks = System.getenv("FPFM_KEYSTORE")
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("FPFM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FPFM_KEY_ALIAS")
            keyPassword = System.getenv("FPFM_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (ks != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // F-Droid wants reproducible builds without Google's dependency blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
