# Building the Android app

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 or 21 | Run Gradle with `JAVA_HOME` pointing at it. The project compiles for Java 17 bytecode. **Verified locally with JDK 21** (Temurin-style tarball). JDK 17 is covered by the CI matrix but was **not** run locally. Use neither a JDK older than 17 nor a very new JDK that Gradle/AGP does not support (a JDK 25 system default is likely to fail). |
| Android SDK | platform `android-35`, build-tools 34+ | Set `ANDROID_HOME` (or create `android/local.properties` with `sdk.dir=/path/to/Sdk`; that file is git-ignored). |
| Gradle | wrapper 9.5.0 | `./gradlew` downloads it on first use from services.gradle.org. |
| Android Gradle Plugin / Kotlin | 8.5.2 / 1.9.24 (Compose compiler 1.5.14) | pinned in `android/build.gradle.kts`, `android/app/build.gradle.kts` |

App: `applicationId`/namespace `com.smartring.testbed`, `minSdk 26`, `targetSdk`/`compileSdk 35`. Source packages are `com.smartring.*`.

## Commands

```bash
export JAVA_HOME=/path/to/jdk-17-or-21
export ANDROID_HOME=$HOME/Android/Sdk
cd android
./gradlew testDebugUnitTest      # unit tests (JVM + Robolectric), no device
./gradlew assembleDebug          # APK: android/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or from the repo root: `tools/check_all.sh` (Python tests + Android tests + debug build).

Verified in this repository's release preparation (2026-11-25, JDK 21, Gradle 9.5.0, **offline** with a warm Gradle cache and a local SDK):
`./gradlew --offline testDebugUnitTest` passes (61 tests). A **cold** build needs network access to Google's Maven repository, Maven Central and the Gradle plugin portal;
that was not exercised here, nor was a clean-machine build in CI (the workflow is written but unrun until the repo is pushed).

## Release builds

`assembleRelease` is configured without minification and without a signing config: you get an unsigned APK. Signing keys never belong in the repo (`*.jks`, `*.keystore` are git-ignored).

## First run on a phone

1. Install the APK, open **Ring**. Grant the Bluetooth permissions (Android 12+: Nearby devices; Android 11 and older: Location is requested for BLE scanning).
2. Make sure the ring is not held by the vendor app: force-stop it, or turn off its Bluetooth connection. A ring talks to one central at a time.
3. Tap **Connect**. The app finds a device whose name contains `SY01`, first among bonded devices, then by scanning.
4. Gestures tab -> API card -> enable, to use the HTTP/WebSocket API ([API.md](API.md)).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Unsupported class file major version` / Gradle crashes at start | Wrong `JAVA_HOME`. Use JDK 17 or 21. |
| `SDK location not found` | Set `ANDROID_HOME` or `android/local.properties`. |
| `Could not resolve ...` offline | Cold cache; build once online. |
| Ring "not found" | Vendor app is connected to it, ring asleep (tap it), or Bluetooth off. |
| `UnsatisfiedLinkError jl_ota_auth` in logs | Expected: the optional vendor library is not shipped; it only affects an auth probe the SY01 does not need. |
