plugins {
    id("com.android.application") version "9.4.0" apply false
    // Kotlin is AGP's built-in one from 9.0 on: the Kotlin Android plugin is not applied anywhere,
    // this line only puts a newer Kotlin Gradle plugin on the build classpath than AGP's default.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
