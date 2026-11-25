# Device acceptance checklist

The hardware acceptance rows below remain unperformed; web checks are recorded separately below. Record phone model, Android version, app commit, hub, adapter, controller firmware, network path, duration and result in a local report. Keep device identifiers and private addresses out of public commits.

| Test | Procedure | Pass condition |
| --- | --- | --- |
| Install / permissions | Install debug APK; deny camera, then grant it on next Start | No crash; explicit permission/error status; telemetry-only mode works |
| Persistence | Configure peers, baud and video; save; force-stop and reopen | Saved values return; service remains stopped |
| USB adapters | Connect each CP210x, CH340/341, FTDI, CDC ACM; grant permission | Correct driver/port; heartbeat and parameter read succeed |
| USB reconnect | Unplug during routing; reconnect; grant permission if required | Only USB retries; UDP peers stay active; traffic resumes |
| Serial rates | Repeat at 57600, 115200, 230400, 460800 and 921600 with matching firmware | No unexpected CRC errors or adapter failures |
| MAVLink variants | Test firmware ports in MAVLink 1, MAVLink 2 and signed MAVLink 2 | GCS accepts unchanged packets/signatures; no signature rewrite |
| SITL / GCS | Follow docs/SITL.md; read a parameter through the phone | Current heartbeat and bidirectional routing |
| Multiple peers | Connect two GCS peers; stop one; resume it | Other peer remains live; no stale queued commands replay |
| Peer change | Stop vehicle UDP peer for >5 s; use a new sender | Parser/queue reset; old packets do not reach the new peer |
| Camera discovery | Enumerate all Camera2 IDs and sizes | Listed capabilities match device; unsupported choices report an error |
| Preview | Select each built-in camera; preview; close preview | Image visible; focus/exposure/zoom match supported settings; resource released |
| Synthetic video | Select synthetic source; stream and preview separately | Moving test pattern; no simulated vehicle status |
| Encoder / RTP | Receive with GStreamer; then Mission Planner; use 1 s keyframes | Stable H.264 display; recovery after induced packet loss |
| Slow video consumer | Constrain video bandwidth while fetching parameters | Bounded queue drops; keyframe recovery; telemetry continues |
| Independent failure | Use invalid video destination or unsupported size; route MAVLink | Video error/retry does not stop telemetry |
| Network recovery | Disconnect Wi-Fi, restore; switch cellular/Wi-Fi; reconnect Ethernet | Socket reopening and resumed telemetry without Activity restart |
| ZeroTier | Follow docs/NETWORK.md; interrupt VPN then restore | Explicit peers reconnect through VPN; GCS reads parameters |
| Activity lifetime | Home, rotate, destroy Activity using developer options | Notification and runtime traffic remain live |
| Screen off | Lock phone for 30 min with telemetry and video enabled | Traffic continues; log any OEM restrictions |
| Stop / restart | Use notification Stop; immediately start from Activity | Serial, camera, codec and sockets release; clean restart |
| Process / reboot | Kill process, force-stop and reboot separately | No false claim of recovery; user can restart visibly |
| Endurance | Operate 2 h on intended powered hub | Record battery, charging, thermal status, traffic continuity and faults |

Inspect the receiver for RTP payload 96, 90 kHz timestamps, sequence progression, final-packet marker, FU-A start/end bits and SPS/PPS before IDR. Capture a signed MAVLink frame at each side and compare the complete bytes. Do not use a live vehicle's arm command as a test. Keep independent ArduPilot failsafes active.

## Web administration acceptance

Automated checks: `./gradlew :core:test :webserver:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, `node --test tests/web-ui.mjs`, and syntax checks for both browser scripts. App JVM tests cover a queued resource/state commit after service destruction, commit dispatch to the lifecycle thread, and cancellation of a pending request.

Device HTTP checks on 2026-11-25: login page accessible without credentials; APIs reject unauthenticated requests; configured login accepted; admin/config/status readable after login; logout revokes the session. APK installed on the attached phone. Unauthenticated encoded and repeated-slash admin/API URLs returned 401 after the correction. User confirmed the page worked. Browser automation host unavailable, so no automated visual claim is made.

Manual checks still to perform:
- On phone and desktop, sign in with wrong/correct credentials and navigate every section. Check no horizontal overflow and keyboard focus visibility.
- Change a saved setting, reload it, and confirm persistence. During Save, fields should be disabled; after a rejected save they should unlock with edits retained.
- In a private browser tab, `/admin` must show login. After logout, Back/reload must not restore an active admin session.
- Stop/start the Android service; the previous browser session must require login again.
- With video disabled, repeatedly Apply or Pause in the browser while pressing notification Stop on the phone. After Stop, no runtime or wake lock should return; restart visibly and confirm Apply/Pause still work. The automated scheduling tests do not replace this device check.
- Perform the SITL procedure in `docs/SITL.md`; validate current heartbeat, counters and parameter fetch through the phone, without a direct simulator-to-GCS shortcut.
