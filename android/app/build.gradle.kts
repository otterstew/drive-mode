plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thewishingstream.drive"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thewishingstream.drive"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes {
        // Signed with this Mac's debug key: its fingerprint is what Spotify's
        // dashboard is given, so keep building on this Mac.
        getByName("debug") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Spotify App Remote: controls the Spotify app without bringing it to the front.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
}
