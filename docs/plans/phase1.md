# Phase 1 implementation plan

The supplied specification authorizes implementation. Use a JVM core and one Android app module. Do not add empty modules. Forward immutable wire frames, including signatures. Decode only diagnostics. Bounded per-peer queues expire packets before reconnect. Loop suppression uses a bounded monotonic-time cache. Unknown dialect IDs pass through with unverified CRC diagnostics.

1. Bootstrap Gradle, install local SDK tools, verify dependency artifacts.
2. Test parser fragmentation, CRC errors, signed frames, loop suppression, overflow, expiry, RTP fragmentation, and UDP round trips before implementation.
3. Implement tested JVM protocol, router, UDP transport, configuration, and RTP components.
4. Build Android foreground runtime, DataStore, USB discovery/permission/serial, network and battery diagnostics. Isolate each transport with SupervisorJob and bounded backoff.
5. Implement Camera2 surface encoder and bounded RTP consumer. Preview uses a separate Activity-owned camera session and cannot interrupt running video.
6. Build Compose settings and diagnostics with real observed counters. Apply configuration while stopped.
7. Run unit tests, Android build and lint; fix findings. Document SITL, ZeroTier, wiring, installation and exact device checks.

Future phases remain unimplemented. GNSS is disabled and unavailable in Phase 1. No web server or control injection endpoint is exposed.
