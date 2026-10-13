plugins {
    id("com.android.application")
}

android {
    namespace = "com.googletv.screensaver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.googletv.screensaver"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("com.hierynomus:smbj:0.15.0")
}
