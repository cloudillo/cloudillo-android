import java.util.Properties

plugins {
	id("com.android.application")
}

// Release signing: each CLOUDILLO_* value comes from the environment, else from the untracked
// keystore.properties in the repo root (same keys). Without CLOUDILLO_KEYSTORE the release APK is
// unsigned, which is what F-Droid builds. See claude-docs/FDROID.md "Signing".
val keystoreProps = Properties().apply {
	rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String): String? = System.getenv(key) ?: keystoreProps.getProperty(key)
val releaseKeystore = signingValue("CLOUDILLO_KEYSTORE")

android {
	namespace = "org.cloudillo.app"
	// Keep in sync with `platform` / `buildTools` in shell.nix
	compileSdk = 37
	buildToolsVersion = "37.0.0"

	defaultConfig {
		applicationId = "org.cloudillo.app"
		minSdk = 29
		targetSdk = 37
		// versionName = Cloudillo monorepo version; versionCode = major*10000 + minor*100 + patch
		versionCode = 822
		versionName = "0.8.22"
	}

	signingConfigs {
		if (releaseKeystore != null) {
			create("release") {
				storeFile = rootProject.file(releaseKeystore)
				storePassword = signingValue("CLOUDILLO_KEYSTORE_PASSWORD")
				keyAlias = signingValue("CLOUDILLO_KEY_ALIAS")
				keyPassword = signingValue("CLOUDILLO_KEY_PASSWORD")
			}
		}
	}

	buildTypes {
		debug {
			// Installs beside release; label comes from src/debug/res
			applicationIdSuffix = ".debug"
		}
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			// null (no key configured) -> app-release-unsigned.apk
			signingConfig = signingConfigs.findByName("release")
			// Reproducibility: no META-INF/version-control-info.textproto (commit hash) in the APK
			vcsInfo {
				include = false
			}
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	// Built-in Kotlin defaults its jvmTarget to targetCompatibility (17)
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		buildConfig = true
	}

	// F-Droid rejects the encrypted dependency-info blob
	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}
}

// Exactly three runtime dependencies: kotlin-stdlib (implicit), coroutines, webkit. Nothing else.
// junit and org.json are test-only and never ship (android.jar's org.json is a stub on the JVM).
dependencies {
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
	implementation("androidx.webkit:webkit:1.18.0")
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.json:json:20250517")
}
