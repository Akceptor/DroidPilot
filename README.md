# DroidPilot

Native Android companion computer for ArduPilot Rover, Copter and Plane. Phase 1 provides USB/UDP MAVLink routing and Camera2 hardware H.264 streaming. No root, Raspberry Pi, account or cloud service is required. ZeroTier runs as a separate app.

## Implemented

- Compose / Material 3 dashboard and configuration screens.
- DataStore configuration. Stop the service before applying changes.
- Foreground runtime with a persistent Stop notification and optional partial wake lock.
- USB Host permission and discovery; CP210x, CH34x, FTDI and CDC ACM through usb-serial-for-android.
- Configurable 8N1 serial link, five baud rates, adapter and port selection, bounded reconnect backoff.
- Bidirectional MAVLink 1/2 routing, signed frame preservation, CRC diagnostics, bounded loop suppression and independent short-lived output queues.
- UDP vehicle listener and up to 16 explicit bidirectional UDP peers.
- Real traffic counters, heartbeat age, vehicle system/type, network interfaces, battery and thermal diagnostics.
- Camera2 enumeration, stopped-service preview, hardware H.264 surface encoding, RTP/H.264 UDP output and an explicit synthetic video source.
- Focus, exposure, supported stabilization and zoom controls. Unsupported combinations report an error.

This is an initial implementation. Android device behavior has **not** been verified. See [known limitations](docs/KNOWN_LIMITATIONS.md) and [manual verification](tests/MANUAL.md). Additional phases are not implemented.

## Build and install

Requirements: JDK 25 for the verified Gradle runtime (JDK 17+ is supported by AGP), Android SDK 36, Build Tools 36.0.0, platform-tools and internet access for dependency download. Android Studio is optional. Install SDK packages with `sdkmanager`; accept their licenses. Set `ANDROID_HOME` or set `sdk.dir` in an ignored `local.properties` file.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android API: 28. Compile and target API: 36, the latest stable base platform found in the SDK repository during setup. Debug APKs use the local Android debug signing key. Do not use them as a production release.

1. Open DroidPilot. Configure MAVLink input and remote peers.
2. For USB, select an adapter and grant USB permission.
3. For video, select a supported camera, size and frame rate. Set the destination IP and port. Close preview before starting the runtime.
4. Save configuration and press Start while the Activity is visible. Grant camera and notification permissions when requested.
5. Open the dashboard. Check actual heartbeat and traffic counters. Use Stop or the notification action to release resources.

See [ArduPilot](docs/ARDUPILOT.md), [SITL](docs/SITL.md), [ZeroTier / Mission Planner](docs/NETWORK.md), [hardware](docs/HARDWARE.md), [architecture](docs/ARCHITECTURE.md), [dependency verification](docs/DEPENDENCIES.md) and [roadmap](docs/ROADMAP.md).
