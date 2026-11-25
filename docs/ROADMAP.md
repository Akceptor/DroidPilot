# Roadmap

## Phase 1 — software implemented; device acceptance pending

- Android API 28+ project, SDK 36 target, Compose dashboard/settings, DataStore.
- Foreground service and permissions, independent runtime lifetime, Stop notification.
- USB discovery/permission and serial drivers; reconnect when hardware and permission permit.
- MAVLink 1/2 framing, signatures retained, diagnostics and bounded bidirectional USB/UDP routing.
- Multiple explicit UDP peers, bounded backoff, network diagnostics and socket reopening.
- Camera2 discovery, stopped-service preview, hardware H.264 surface encoder, RTP/H.264 UDP and synthetic video.
- JVM tests and manual SITL, Mission Planner, VPN and hardware procedures.

Device acceptance, Mission Planner acceptance and real SITL-to-phone acceptance remain open. A build is not proof of hardware operation.

## Phase 2 — not implemented

UVC library evaluation and simultaneous USB serial/camera tests. Add MJPEG, native WebRTC and MP4 recording with independent consumers. Investigate compatible UVC H.264 passthrough. Validate WebRTC's external encoder interface before attempting shared encoding. Add rotation/flip transforms and optional H.265 after device capability tests.

## Phase 3 — not implemented

Android GNSS acquisition and tested GPS_INPUT generation. Correct timestamps, altitude reference, NED velocity, ignore flags and expiry are required. Add phone battery MAVLink telemetry and permission-aware cellular signal diagnostics. Basic local battery/thermal status is already available in Phase 1; add thermal load management here.

## Phase 4 — not implemented

Evaluate Ktor's Android-compatible engine and dependencies. Implement port 4001 HTTP service, static browser UI, authenticated REST and WebSocket telemetry/signaling. Require exclusive command ownership, command expiry and release on disconnect before keyboard/gamepad control.

## Phase 5 — not verified

Long-duration operation, USB recovery, screen-off operation, cellular/VPN interruption, process termination, reboot restrictions, thermal load and power tests. Investigate allowed recovery paths; do not promise camera startup from boot.

Recommended next step: run tests/MANUAL.md on one target phone, UART adapter and PD hub. Fix device-specific Phase 1 faults before adding UVC.
