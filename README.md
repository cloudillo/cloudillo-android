# Cloudillo for Android

Native Android client for [Cloudillo](https://cloudillo.org): enter your idTag once, then the app
shows your Cloudillo shell (`https://<idTag>/`) in a WebView with file upload, camera/mic access and
system integration. No Google Play Services; built for F-Droid.

## Build

The toolchain (JDK 21, Android SDK) comes from a pinned `shell.nix`:

```sh
nix-shell
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Releases

Releases are reproducible builds signed with the developer key and published on F-Droid and as
GitHub release APKs (the same file). The version follows the Cloudillo monorepo version; tags are
`v<versionName>`. Store metadata lives in `fastlane/metadata/android/`. Procedure, signing setup and
the F-Droid recipe: `claude-docs/FDROID.md`.

## License

MPL-2.0, see `LICENSE`.
