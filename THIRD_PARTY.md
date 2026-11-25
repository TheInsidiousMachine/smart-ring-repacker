# Third-party components

This repository's original code is MIT-licensed (see LICENSE). It builds on and interoperates with the following. Licences are as published by the upstream
projects; check them before redistributing.

## Libraries used by the Android app (`android/app/build.gradle.kts`)

| Library | Version | Licence |
|---|---|---|
| AndroidX core-ktx, appcompat, constraintlayout, activity-compose, lifecycle (runtime-compose, viewmodel-compose) | 1.13.1 / 1.7.0 / 2.1.4 / 1.9.0 / 2.8.3 | Apache-2.0 |
| Jetpack Compose (BOM 2024.06.00: ui, material3, material-icons-extended, tooling) | BOM 2024.06.00 | Apache-2.0 |
| Material Components for Android | 1.12.0 | Apache-2.0 |
| kotlinx.coroutines (android, test) | 1.8.1 | Apache-2.0 |
| kotlinx.serialization (json) | 1.6.3 | Apache-2.0 |
| OkHttp (and MockWebServer for tests) | 4.12.0 | Apache-2.0 |
| Ktor server (core, CIO, websockets) | 2.3.12 | Apache-2.0 |
| JUnit 4 (tests) | 4.13.2 | EPL-1.0 |
| Robolectric (tests) | 4.13 | MIT |
| AndroidX Test (core, compose ui-test-junit4) (tests) | 1.6.1 / BOM | Apache-2.0 |

Build tooling: Android Gradle Plugin 8.5.2, Kotlin Gradle plugin 1.9.24 (Apache-2.0); Gradle wrapper (Apache-2.0; `android/gradle/wrapper/gradle-wrapper.jar` is the standard upstream wrapper).
Transitive dependencies carry their own licences (mostly Apache-2.0).

## Python and tools (not bundled; installed by you)

| Tool | Used by | Licence |
|---|---|---|
| pytest | tests | MIT |
| bleak (optional) | `client.py` | MIT |
| python-evdev (optional) | `pc/ring_bridge.py` | BSD-3-Clause |
| ydotool (optional, external program) | `pc/ring_bridge.py` fallback | see upstream (AGPL-3.0 as far as we know; not bundled) |
| numpy | `fw/ring_app/model/*.py` | BSD-3-Clause |
| Unicorn (+ bindings) | `fw/ring_app/emu/*.py` | GPL-2.0 (not bundled; you install it) |
| Arm GNU Toolchain (arm-none-eabi-gcc) | firmware build | GPL with runtime-library exceptions (not bundled) |

## Vendor artifacts: NOT included

This repository does **not** contain, and you must not commit:

- the vendor Android app (RWFit, `com.rw.revivalfit`) or any decompiled source of it (`rwfit_apk/` is git-ignored);
- vendor firmware images (`firmware/*.bin`) or images built from them;
- the vendor's native library `libjl_ota_auth.so` (JieLi RCSP auth). The class `com.jieli.jl_bt_ota.impl.RcspAuth` is only a small JNI binding that must carry the vendor's class name;
  it is inert without a library you supply yourself, and the SY01 does not need it.

Protocol facts, command identifiers and firmware addresses in `docs/` and `command_table.py` / `CommandTable.kt` were derived by analysing those artifacts for
interoperability with a ring the author owns. No vendor code or data files are reproduced. Names such as RWFit, PixArt, JieLi, LinkedSemi, Colmi and SY01 belong to their owners;
this project is not affiliated with or endorsed by them.

## Research referenced (not copied)

Colmi ring protocol write-ups and clients, cited in [docs/ADDING_A_RING.md](docs/ADDING_A_RING.md): `tahnok/colmi_r02_client` (MIT), Puxtril's `colmi-docs`, Gadgetbridge (AGPL-3.0), atc1441's ring tools (GPL-3.0).
