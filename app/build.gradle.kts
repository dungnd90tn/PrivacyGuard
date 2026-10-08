plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.privacyguard.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.privacyguard.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.privacyguard.android.MvpInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    lint { abortOnError = true; warningsAsErrors = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM tests exercise the real policy JSON codec instead of Android's stub implementation.
    testImplementation("org.json:json:20240303")
}
