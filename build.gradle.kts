// AGP 9 compiles Kotlin itself (built-in Kotlin); the classpath entry only pins the
// Kotlin Gradle plugin / compiler version it uses instead of AGP's bundled default.
buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
	}
}

plugins {
	id("com.android.application") version "9.5.1" apply false
}
