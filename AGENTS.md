# DroidPilot agent instructions

The parent Drones/AGENTS.md applies. Use Akceptor's noreply identity. Never commit keys, device IDs, network membership data, private IP configuration or credentials. Do not push unless requested. Do not change other Drones projects.

## Architecture

- `core` is Kotlin/JVM. It must not import Android classes.
- `app` owns hardware adapters, DataStore, foreground lifecycle and Compose.
- Keep capture, encoding and transmission separate. Each output needs its own bounded queue and keyframe recovery policy.
- Preserve forwarded wire bytes and signatures. Decode a separate view for diagnostics. Never silently authenticate a signed packet.
- No automatic arm, failsafe bypass, stale command replay or unauthenticated remote control endpoints.
- Use structured concurrency, SupervisorJob subsystem boundaries and monotonic time for deadlines. Bound logs, caches and queues.
- Activity destruction must not stop the companion service. Close every hardware resource during cancellation, including asynchronous acquisition.
- Do not add empty modules or dependencies for deferred phases. Verify artifacts and Android compatibility first.

## Changes and verification

Use Kotlin, Compose Material 3 and Gradle Kotlin DSL. Keep files focused. Write behavior tests for protocol, routing and buffering changes. Run `./gradlew :core:test :app:assembleDebug :app:lintDebug`. Do not suppress lint errors to hide an incompatible API. Document manual device checks in tests/MANUAL.md. Report automated results separately from unperformed device tests. Never show synthetic telemetry as a real connection.
