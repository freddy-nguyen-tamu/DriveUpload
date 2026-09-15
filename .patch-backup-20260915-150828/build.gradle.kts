plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = providers.environmentVariable("XONG_KEYSTORE").orNull
val storePasswordValue = providers.environmentVariable("XONG_STORE_PASSWORD").orNull
val keyAliasValue = providers.environmentVariable("XONG_KEY_ALIAS").orNull
val keyPasswordValue = providers.environmentVariable("XONG_KEY_PASSWORD").orNull
val isReleaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

if (isReleaseRequested && listOf(keystorePath, storePasswordValue, keyAliasValue, keyPasswordValue).any { it.isNullOrBlank() }) {
    throw GradleException(
        "Release signing is required so the APK SHA-1 matches your Google OAuth Android client. " +
            "Set XONG_KEYSTORE, XONG_STORE_PASSWORD, XONG_KEY_ALIAS and XONG_KEY_PASSWORD."
    )
}

android {
    namespace = "com.xong.driveupload"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xong.driveupload"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue

            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
