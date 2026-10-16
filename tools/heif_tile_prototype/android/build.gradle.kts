plugins {
    id("com.android.application")
}

android {
    namespace = "com.googletv.heiftileprobe"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.googletv.heiftileprobe"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += listOf("armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.31.6"
        }
    }
}
