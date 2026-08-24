plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.pocketmagnifier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.pocketmagnifier"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }
}

dependencies {
    // ML Kit Text Recognition (on-device OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // ML Kit On-Device Translation
    implementation("com.google.mlkit:translate:17.0.3")
    // ML Kit Language Identification (detect source language)
    implementation("com.google.mlkit:language-id:17.0.6")
}
