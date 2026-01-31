// Top-level build.gradle.kts
buildscript {
    dependencies {
        classpath("com.google.gms:google-services:4.4.0")
    }
}

// Top-level build.gradle.kts
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.google.gms.google-services") version "4.4.1" apply false
}