import java.io.ByteArrayOutputStream
import java.time.Instant

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

// A shallow clone counts fewer commits, so a release-signed build from one could carry a lower
// versionCode than the last Release and be refused by the phone as a downgrade. Refuse first.
if (hasReleaseKey && git("rev-parse", "--is-shallow-repository") == "true") {
    throw GradleException("Release-signed builds need the full git history: run `git fetch --unshallow` first")
}

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

/**
 * `gradlew sbom` writes app/build/reports/bom.json: a CycloneDX 1.5 software bill of materials
 * of everything on the release runtime classpath, attached to every Release. Written here
 * rather than by a plugin so the build itself pulls in nothing extra.
 */
tasks.register("sbom") {
    val out = layout.buildDirectory.file("reports/bom.json")
    outputs.file(out)
    outputs.upToDateWhen { false }         // timestamped and classpath-dependent: never reuse a stale one
    doLast {
        val comps = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .distinctBy { "${it.group}:${it.name}:${it.version}" }
            .sortedBy { "${it.group}:${it.name}" }
            .joinToString(",\n") {
                """    {"type": "library", "group": "${it.group}", "name": "${it.name}", "version": "${it.version}", "purl": "pkg:maven/${it.group}/${it.name}@${it.version}", "bom-ref": "pkg:maven/${it.group}/${it.name}@${it.version}"}"""
            }
        val version = android.defaultConfig.versionName
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "version": 1,
  "metadata": {
    "timestamp": "${Instant.now()}",
    "component": {"type": "application", "name": "CrewRadio", "version": "$version", "bom-ref": "pkg:generic/CrewRadio@$version", "licenses": [{"license": {"id": "EUPL-1.2"}}]}
  },
  "components": [
$comps
  ]
}
"""
        )
    }
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
