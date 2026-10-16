import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

plugins {
    id("com.android.application")
}

val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile
fun gitOutput(vararg arguments: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git", "-C", repositoryRoot.path) + arguments)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    output.takeIf { process.waitFor() == 0 }
}.getOrNull()

val commitTag = gitOutput("rev-parse", "--short=8", "HEAD") ?: "unknown"
val workingTreeDirty = gitOutput("status", "--porcelain")?.isNotEmpty() == true
val buildTag = commitTag + if (workingTreeDirty) "-dirty" else ""
val buildTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.US)
    .withZone(ZoneId.systemDefault())
    .format(Instant.now())

android {
    namespace = "com.googletv.screensaver"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.googletv.screensaver"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
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
            path = file("../../../tools/heif_tile_prototype/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

dependencies {
    implementation("com.hierynomus:smbj:0.15.0")
}
