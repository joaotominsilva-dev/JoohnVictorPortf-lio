plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.charis.shoot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.charis.shoot"
        minSdk = 28
        targetSdk = 35
        // O GitHub Actions passa o número da compilação: cada APK publicado tem versão maior.
        val build = (System.getenv("CHARIS_BUILD") ?: "0").toInt()
        versionCode = 10 + build
        versionName = "1.2.$build"
    }

    signingConfigs {
        create("charis") {
            storeFile = rootProject.file("keystore/charis-shoot.jks")
            storePassword = "charisshoot"
            keyAlias = "charis"
            keyPassword = "charisshoot"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("charis")
        }
        debug {
            signingConfig = signingConfigs.getByName("charis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okio:okio:3.9.1")
}
