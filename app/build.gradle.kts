import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val playKeystoreFile = providers.environmentVariable("SPORTCAM_KEYSTORE_FILE").orNull
val playKeystorePassword = providers.environmentVariable("SPORTCAM_KEYSTORE_PASSWORD").orNull
val playKeyAlias = providers.environmentVariable("SPORTCAM_KEY_ALIAS").orNull
val playKeyPassword = providers.environmentVariable("SPORTCAM_KEY_PASSWORD").orNull
val hasPlaySigning = listOf(playKeystoreFile, playKeystorePassword, playKeyAlias, playKeyPassword).all { !it.isNullOrBlank() }

// Chave fixa para as builds de teste. Sem ela, o AGP inventa um debug.keystore novo em cada máquina
// — e cada APK publicado pelo CI sai com assinatura diferente, o que obriga a desinstalar a versão
// anterior para atualizar. Como o app guarda servidor, chave do YouTube e os cantos do placar em
// preferências com backup desligado, desinstalar apaga a configuração inteira.
//
// O keystore vem do secret SPORTCAM_DEBUG_KEYSTORE, que o workflow decodifica para um arquivo. Sem a
// variável definida, o comportamento é o de antes: cada build assina com uma chave nova. As senhas
// têm o valor convencional de keystore de debug e podem ser trocadas pelo ambiente.
val debugKeystoreFile = providers.environmentVariable("SPORTCAM_DEBUG_KEYSTORE_FILE").orNull
val debugKeystorePassword = providers.environmentVariable("SPORTCAM_DEBUG_KEYSTORE_PASSWORD").orNull ?: "android"
val debugKeyAlias = providers.environmentVariable("SPORTCAM_DEBUG_KEY_ALIAS").orNull ?: "androiddebugkey"
val debugKeyPassword = providers.environmentVariable("SPORTCAM_DEBUG_KEY_PASSWORD").orNull ?: "android"
val hasFixedDebugSigning = !debugKeystoreFile.isNullOrBlank() && file(debugKeystoreFile).exists()

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
        versionCode = 10
        versionName = "0.0.10"
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
        if (hasFixedDebugSigning) {
            getByName("debug") {
                storeFile = file(requireNotNull(debugKeystoreFile))
                storePassword = debugKeystorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
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
    // org.json vem do Android no aparelho, mas o android.jar dos testes unitários é o mockável:
    // toda chamada lançaria "Stub!". Esta é a mesma implementação, só que de verdade, e vale
    // apenas para os testes — o app continua usando a do sistema, sem nada a mais no APK.
    testImplementation("org.json:json:20240303")
}
