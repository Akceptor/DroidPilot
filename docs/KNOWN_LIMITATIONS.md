# Known limitations

## Verified in this environment

- No Android phone or emulator was connected. USB, capture, encoder, screen-off, VPN and Mission Planner tests were not performed.
- The core unit/integration tests run on JVM loopback. They do not prove Android transport or hardware behavior.
- UVC, MJPEG, WebRTC, recording, GNSS forwarding, HTTP service, browser control, TCP and MAVLink WebSocket transports are absent.
- Configuration changes need Stop and Start. Dynamic encoder bitrate adjustment is not exposed.
- Preview needs the service stopped. Camera output uses sensor orientation; rotation and flip transforms are absent.
- H.264 is the only encoder output. Hardware availability and requested camera/encoder combinations are checked at runtime. Failure is reported; there is no silent software encoder fallback. On API 28 the hardware test uses codec-name heuristics because the hardware flag is unavailable.
- There is no RTCP feedback, RTP retransmission, packet-delivery acknowledgment or automatic bitrate adaptation. UDP send counters do not prove reception. Lost video packets recover at a future keyframe.
- No companion heartbeat is generated. Local system/component IDs are reserved for future trusted injection.
- CRC verification uses the pinned ArduPilot dialect table. Unknown dialect IDs are forwarded and counted as CRC unverified. Signatures are preserved but not authenticated.
- The two-second exact-frame loop cache can suppress legitimate duplicate packets. It does not make arbitrary cyclic router topologies safe.
- UART is 8N1 without hardware flow control. USB selection uses VID/PID plus port, so identical adapters are ambiguous. Detach can revoke Android USB permission.
- UDP listener pins the first peer and may change it after five seconds of silence. Use a trusted network. Endpoints require numeric IPs and matching source ports. No address discovery or authentication is implemented for MAVLink UDP.
- Buffers expire ordinary queued packets after 250 ms and incomplete frames after 250 ms. A kernel/socket/device can still buffer bytes outside the app's queues. This app cannot enforce application-level command expiry inside arbitrary forwarded MAVLink messages.
- The service is START_NOT_STICKY. Process death, force-stop and reboot require visible user restart. There is no boot receiver. No automatic camera recovery after boot is claimed.
- Partial wake lock is optional and defaults on. Android/OEM background and battery rules still apply. Thermal status is displayed; thermal throttling policy is not implemented.
- Cellular transport type is listed. Cellular signal strength is not acquired in Phase 1.
- Diagnostic export includes local interface addresses and connection events. Review it before sharing.

## Unverified hardware assumptions

USB host plus PD charging, adapter baud support, multiple ports, hub stability and simultaneous UVC/serial access depend on hardware. Camera permission behavior after lock, sustained Camera2 capture, encoder surface handling, sync-frame requests, SPS/PPS output, network recovery and battery/thermal endurance need the device checklist. API 28 and current Android foreground-service behavior need separate physical-device coverage.

Dependency update notices from lint are expected for pinned stable versions. Upgrade only with a build, lint and device regression check. See DEPENDENCIES.md.
