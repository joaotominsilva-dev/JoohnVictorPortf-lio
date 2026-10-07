import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Com -PtargetAbi=x86_64 (usado no teste do emulador) o build gera um único APK para essa ABI.
// Sem a propriedade, o release gera um APK por ABI: o motor (Python, FFmpeg, QuickJS) é nativo
// e um APK universal passaria de 200 MB.
val targetAbi: String? = providers.gradleProperty("targetAbi").orNull
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

android {
    namespace = "com.joohn.baixavideos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joohn.baixavideos"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (targetAbi != null) {
            ndk { abiFilters += targetAbi }
        }
    }

    splits {
        abi {
            isEnable = targetAbi == null
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        // Chave do projeto, versionada de propósito para que toda build (local ou CI) tenha a
        // mesma assinatura e o app possa ser atualizado sem desinstalar. Para usar uma chave
        // privada, defina as variáveis BV_KEYSTORE_* no ambiente de build.
        create("release") {
            storeFile = file(System.getenv("BV_KEYSTORE_FILE") ?: "keystore/baixavideos.jks")
            storePassword = System.getenv("BV_KEYSTORE_PASSWORD") ?: "baixavideos"
            keyAlias = System.getenv("BV_KEY_ALIAS") ?: "baixavideos"
            keyPassword = System.getenv("BV_KEY_PASSWORD") ?: "baixavideos"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // O youtubedl-android executa binários (libpython.so, libffmpeg.so, libqjs.so) a partir
        // da pasta de bibliotecas nativas, então elas precisam ser extraídas na instalação.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
