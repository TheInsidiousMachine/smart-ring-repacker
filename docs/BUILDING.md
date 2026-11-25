# Building the Android app

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 or 21 | Run Gradle with `JAVA_HOME` pointing at it. The project compiles for Java 17 bytecode. CI builds with both 17 and 21. Newer JDKs are not supported by this Gradle/AGP combination. |
| Android SDK | platform `android-35`, build-tools 34.0.0 (AGP 8.5 default) | Set `ANDROID_HOME` (or create `android/local.properties` with `sdk.dir=/path/to/Sdk`; that file is git-ignored). Android Studio installs it; without Studio see below. |
| adb | platform-tools | To install the APK on your phone (USB debugging on, or wireless debugging). |
| Gradle | wrapper 9.5.0 | `./gradlew` downloads it on first use from services.gradle.org. |
| Android Gradle Plugin / Kotlin | 8.5.2 / 1.9.24 (Compose compiler 1.5.14) | pinned in `android/build.gradle.kts`, `android/app/build.gradle.kts` |

### SDK without Android Studio

Download the "command line tools only" zip from https://developer.android.com/studio#command-tools, unpack it to
`$ANDROID_HOME/cmdline-tools/latest/`, then:

```bash
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;34.0.0" "platform-tools"
```

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

Verified 2026-11-25: `./gradlew testDebugUnitTest assembleDebug` passes (59 tests) locally with JDK 21, and on GitHub Actions from a cold cache with JDK 17 and 21
(`.github/workflows/ci.yml`). A cold build needs network access to Google's Maven repository, Maven Central and the Gradle plugin portal.

## Release builds

`assembleRelease` is configured without minification and without a signing config: you get an unsigned APK. Signing keys never belong in the repo (`*.jks`, `*.keystore` are git-ignored).

## First run on a phone

1. Install the APK, open **Ring**. Grant the Bluetooth permissions (Android 12+: Nearby devices; Android 11 and older: Location is requested for BLE scanning).
2. Make sure the ring is not held by the vendor app: force-stop it, or turn off its Bluetooth connection. A ring talks to one central at a time.
3. Tap **Connect**. The app finds a device whose name contains `SY01`, first among bonded devices, then by scanning. No separate pairing step is needed.
4. Gestures tab -> API card -> enable, to use the HTTP/WebSocket API ([API.md](API.md)). It starts **local-only** (this phone). Turn on **LAN** to reach it
   from a PC or glasses; they must be on the same Wi-Fi (or the same VPN, e.g. Tailscale) as the phone. The token is shown on the card.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Unsupported class file major version` / Gradle crashes at start | Wrong `JAVA_HOME`. Use JDK 17 or 21. |
| `SDK location not found` | Set `ANDROID_HOME` or `android/local.properties`. |
| `Could not resolve ...` offline | Cold cache; build once online. |
| Ring "not found" | Vendor app is connected to it, ring asleep (tap it), or Bluetooth off. |
