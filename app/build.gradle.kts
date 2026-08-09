import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val playKeystoreFile = providers.environmentVariable("SPORTCAM_KEYSTORE_FILE").orNull
val playKeystorePassword = providers.environmentVariable("SPORTCAM_KEYSTORE_PASSWORD").orNull
val playKeyAlias = providers.environmentVariable("SPORTCAM_KEY_ALIAS").orNull
val playKeyPassword = providers.environmentVariable("SPORTCAM_KEY_PASSWORD").orNull
val hasPlaySigning = listOf(playKeystoreFile, playKeystorePassword, playKeyAlias, playKeyPassword).all { !it.isNullOrBlank() }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.cascam"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.cascam"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "0.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasPlaySigning) {
            create("playRelease") {
                storeFile = file(requireNotNull(playKeystoreFile))
                storePassword = playKeystorePassword
                keyAlias = playKeyAlias
                keyPassword = playKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasPlaySigning) signingConfig = signingConfigs.getByName("playRelease")
        }
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    val cameraX = "1.4.2"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    testImplementation("junit:junit:4.13.2")
}
