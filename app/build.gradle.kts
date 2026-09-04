import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** Output of a git command run in the repository, or empty when git is unavailable (a source download, say). */
fun git(vararg args: String): String = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("git", *args)
        standardOutput = out
        isIgnoreExitValue = true
    }
    out.toString().trim()
} catch (_: Exception) { "" }

// Versions come from git, so every merge is a new version without anyone editing a number:
// versionCode is the commit count on the current branch (monotonic on main), versionName is
// 1.<count> and the short commit hash is shown on the Status screen. README: every phone on the
// crew must run the same build, and this is how a phone tells which one it has.
val commitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val commitSha = git("rev-parse", "--short", "HEAD").ifEmpty { "local" }
val dirty = git("status", "--porcelain").isNotEmpty()

// Release signing: a keystore named by CREWRADIO_KEYSTORE (CI decodes it from a secret) or
// app/release.keystore locally, with its passwords from the environment. Without one the
// release build is signed with the debug key so `assembleRelease` always works; the README
// explains why a phone then cannot upgrade between a CI build and a local one.
val keystoreFile = file(System.getenv("CREWRADIO_KEYSTORE") ?: "release.keystore")
val hasReleaseKey = keystoreFile.exists() && System.getenv("CREWRADIO_KEYSTORE_PASSWORD") != null

android {
    namespace = "fi.crewradio"
    compileSdk = 34
    defaultConfig {
        applicationId = "fi.crewradio"
        minSdk = 29
        targetSdk = 34
        versionCode = commitCount
        versionName = "1.$commitCount"
        buildConfigField("String", "GIT_SHA", "\"$commitSha${if (dirty) "+" else ""}\"")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = keystoreFile
                storePassword = System.getenv("CREWRADIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CREWRADIO_KEY_ALIAS") ?: "crewradio"
                keyPassword = System.getenv("CREWRADIO_KEY_PASSWORD") ?: System.getenv("CREWRADIO_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/** `gradlew -q printVersion` prints the versionName, for the release workflow to name the APK and the tag. */
tasks.register("printVersion") {
    doLast { println(android.defaultConfig.versionName) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}
