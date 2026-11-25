# Architecture

## Modules

`core` contains configuration, MAVLink framing, CRC extras, routing, bounded queues, UDP transport and RTP packetization. It has no Android dependency. `app` contains the Android adapters, service and UI. `webserver` contains the Android-independent Ktor CIO server, authentication, API and static assets. Packages are used instead of empty feature modules.

## Data flow

USB serial or the vehicle UDP listener → per-input parser → immutable wire frame → loop guard → one queue for each other endpoint → serial/UDP writer.

Each input parser accepts fragmented reads and multiple frames. It supports MAVLink 1 and MAVLink 2, including the 13-byte signature trailer. CRC extras come from ArduPilot's generated dialect table. Unknown messages are forwarded with an unverified-CRC count. Signatures are neither verified nor altered. Unsupported MAVLink 2 incompatibility flags are rejected. Incomplete framing state expires after 250 ms.

A separate frame view reads ArduPilot heartbeats (autopilot enum 3). Traffic counters count received complete frames and successful local writes, not delivery acknowledgments. The dashboard declares a current heartbeat only for five seconds.

The router does not send a frame back to its input. A 4096-entry SHA-256 cache suppresses exact repeats for two seconds. This is a bounded, time-based loop defense, not a topology protocol. Legitimate exact repeats inside that interval are suppressed. Do not connect cyclic router networks. Queues hold 64 packets, drop oldest at overflow and expire packets after 250 ms. Disconnect destroys queues and parser state. UDP peer selection and send use one lock so old packets cannot cross peer changes. Forwarding includes commands from configured GCS peers; there is no web injection endpoint.

A UDP client accepts replies only from its configured numeric address and port. A listener pins its first peer and ignores other addresses while that peer is active. Five seconds of silence permits replacement and resets framing, including when the same address returns. This is not authentication.

## Video

`VideoSource` → Camera2 encoder surface (or explicit EGL synthetic source) → MediaCodec hardware H.264 → copied access units → bounded dependency-aware consumer queue → RFC 6184 RTP UDP.

The sender has an independent coroutine. The queue holds four access units. Overflow or send error invalidates queued dependencies. Non-keyframes are rejected until an IDR arrives. The encoder receives throttled sync requests; SPS/PPS precede keyframes. RTP uses payload type 96, 90 kHz timestamps, FU-A fragmentation and packets of at most 1200 bytes. There is no RTCP or retransmission. MediaCodec output supports Annex B and validated four-byte AVC length prefixes.

Preview is Activity-owned and available while the service is stopped. Runtime capture does not depend on a preview surface. Phase 1 has one encoded output. Later consumers must each get an independent queue. MJPEG needs a raw/JPEG branch. WebRTC must validate its encoder boundary before shared H.264 is designed.

## Runtime

The foreground service owns a SupervisorJob. Each transport and video pipeline retries independently with 250 ms to 8 s bounded backoff. A ten-second healthy run resets backoff. Network callbacks monitor available networks; loss closes UDP sockets for reopening. No socket is bound to a selected Android Network. Normal routing can use a VPN. Video errors do not cancel MAVLink jobs.

Compose observes StateFlow. DataStore persists configuration. Changes apply on the next explicit Start. A snapshot is passed to the service; the service survives Activity destruction. connectedDevice is active for the external MAVLink link. camera is added on API 30+ only for an enabled real camera. No location subsystem or permission is active. The optional partial wake lock is held only while the runtime exists. Android releases process-owned locks on process death.

The service uses START_NOT_STICKY. There is no boot receiver or claim of automatic camera restart after process death/reboot. Start from a visible Activity. OEM power policy can still stop operation.


## Web service ownership

The foreground service owns HTTP separately from `CompanionRuntime`. Pause cancels and joins the runtime, closes hardware and releases its wake lock, while HTTP remains available. Android Stop ends both. Native Start can recreate a paused runtime from a visible Activity. HTTP Apply rejects real-camera starts/restarts, including replacing an existing real-camera pipeline.

DataStore transactions compare configuration revisions atomically before saving. Both native and browser editors use the same map schema. Credentials are excluded from that map and stored as a salted PBKDF2 hash in a separate private AtomicFile. Ktor administration and APIs require cookie sessions; only exact login routes and shared styling are public. Mutation endpoints require a same-origin custom header, bounded bodies and valid numeric/localhost Host names. No vehicle commands are accepted.
