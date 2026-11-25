# Verification record

Initial Phase 1 software verification: 2026-11-25.

Command:

```sh
./gradlew :core:cleanTest :core:test :app:assembleDebug :app:lintDebug
```

Result: BUILD SUCCESSFUL. Twelve tests passed, zero failures or skips. The debug APK was generated. Lint reported zero errors and eight warnings: six dependency/version update notices, one backup-policy advisory and one foreground-runtime wake-lock timeout advisory. The wake lock is released in service destruction; process death also releases process-owned locks. Backup is disabled and explicit extraction rules exclude application data.

Tests cover fragmented and coalesced MAVLink reads, CRC rejection/resynchronization, signed MAVLink 2 preservation, partial-frame expiry, loop-cache expiry/bounds, queue overflow and stale-packet expiry, configuration round trips/validation, bounded backoff, router reflection suppression, bidirectional UDP loopback, RTP fragmentation/marker/reassembly, AVC versus Annex B framing and video keyframe recovery after queue overflow.

A separate source review found four issues. UDP peer sends now share a session lock, partial parser state expires, asynchronous camera acquisition has cancellation cleanup and H.264 framing has a regression test. A second review found no remaining critical or important blockers in those changes.

No phone or emulator was attached. No physical UART, camera, hardware encoder, SITL-to-phone, Mission Planner, ZeroTier, screen-off or endurance tests were run. Use tests/MANUAL.md. Passing JVM tests and APK generation do not establish device acceptance.
