# Dependency verification

Checked during the initial implementation on 2026-11-25. All selected artifacts resolved in the Gradle build. Compilation, Android AAR metadata checks and lint check compatibility. This does not prove vendor hardware behavior. Versions are pinned; they are not claimed to be the newest available releases.

| Dependency | Version | Verification |
| --- | --- | --- |
| Gradle | 9.1.0 | Official wrapper and distribution; supports build runtime Java 25 |
| Android Gradle plugin | 9.0.1 | Official compatibility notes: Gradle 9.1, SDK 36.1 maximum, JDK 17 minimum; embedded Kotlin |
| Kotlin JVM / Compose compiler | 2.2.10 | Aligned with AGP embedded Kotlin; artifacts resolved |
| Compose BOM | 2025.08.01 | Google Maven POM resolved; Material 3/UI compiled |
| Activity Compose | 1.10.1 | Google Maven resolved and lint checked |
| Lifecycle runtime Compose | 2.9.2 | Google Maven resolved and lint checked |
| DataStore Preferences | 1.1.7 | Google Maven resolved and compiled |
| Coroutines | 1.10.2 | Maven Central resolved; JVM tests use real coroutines |
| usb-serial-for-android | 3.12.0 | Current upstream README coordinate, JitPack POM/AAR resolved; API compiled |
| JUnit | 4.13.2 | Maven Central resolved; JVM suite executed |

AndroidX, Kotlin and Gradle have maintained official release channels. Lint identifies newer versions than these conservative pins. USB serial upstream is active and documents CP21xx, CH34x, FTDI and CDC ACM support. USB adapter operation remains unverified.

No UVC, native WebRTC or Ktor dependency is selected yet. Their Android support and maintenance must be verified in the phase that implements them. Empty wrappers around unavailable APIs are not included.

CRC extras were generated from [mavlink/c_library_v2 ardupilotmega.h](https://github.com/mavlink/c_library_v2/blob/5b3150cfdae0caad3841b015fc24a1a81a2a1f84/ardupilotmega/ardupilotmega.h). The table contains message IDs and protocol CRC constants. MAVLink libraries are provided under MIT terms; see THIRD_PARTY_NOTICES.md. The app does not use a full MAVLink decoding dependency.

Primary references: [AGP 9.0 compatibility](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [USB serial upstream](https://github.com/mik3y/usb-serial-for-android), [MAVLink serialization](https://mavlink.io/en/guide/serialization.html), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Gradle Java compatibility](https://docs.gradle.org/9.1.0/userguide/compatibility.html).
