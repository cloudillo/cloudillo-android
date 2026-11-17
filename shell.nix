# Build toolchain for Cloudillo Android: JDK 21 + Android SDK, nothing else.
# Usage: nix-shell                         then ./gradlew assembleDebug
#        nix-shell --arg withEmulator true (adds emulator + system image)
#        nix-shell --arg withDiffoscope true (adds diffoscope for the reproducibility check)
{ withEmulator ? false, withDiffoscope ? false }:

let
	# nixos-unstable, 2026-11-14
	pkgs = import (fetchTarball {
		url = "https://github.com/NixOS/nixpkgs/archive/d4f25a3e562008648d45bcf2d70bcc7422cad84e.tar.gz";
		sha256 = "1m6ndbhf4iaq9vgrl1a5cs41h7h4pwgn98qpx45nsm78kc601ny7";
	}) {
		config = {
			android_sdk.accept_license = true;
			allowUnfree = true;
		};
	};

	# Keep in sync with compileSdk / targetSdk / buildToolsVersion in app/build.gradle.kts
	platform = "37.0";
	buildTools = "37.0.0";

	androidComposition = pkgs.androidenv.composeAndroidPackages ({
		platformVersions = [ platform ];
		buildToolsVersions = [ buildTools ];
		includeNDK = false;
		includeCmake = false;
		includeSources = false;
		includeEmulator = withEmulator;
		includeSystemImages = withEmulator;
	} // pkgs.lib.optionalAttrs withEmulator {
		systemImageTypes = [ "default" ];
		abiVersions = [ "x86_64" ];
	});

	androidSdk = androidComposition.androidsdk;
	jdk = pkgs.jdk21;
in pkgs.mkShell {
	name = "cloudillo-android";

	# gradle is only used to (re)generate the wrapper; build with ./gradlew
	packages = [ androidSdk jdk pkgs.gradle_9 ] ++ pkgs.lib.optional withDiffoscope pkgs.diffoscope;

	shellHook = ''
		export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
		export ANDROID_SDK_ROOT="$ANDROID_HOME"
		# build-tools for apksigner / zipalign
		export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/${buildTools}:$PATH"

		# Full JDK (has jlink) - needed by AGP's JdkImageTransform
		export JAVA_HOME="${jdk.home}"

		# The aapt2 AGP downloads from Maven is a generic-Linux binary; use the SDK's one on NixOS
		# (only strictly needed on NixOS without nix-ld; AGP logs an "experimental option" warning)
		export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildTools}/aapt2"
	'';
}
