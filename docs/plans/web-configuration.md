# Authenticated web configuration implementation

The user approved the browser configuration interface. Implement a Ktor CIO JVM module, with Android integration verified on the attached phone. Keep the foreground service as the owner. Pausing telemetry must leave HTTP available. Android Stop ends the service and HTTP listener.

- Add configuration fields for web enable/bind/port and shared map/revision conversion. Preserve existing DataStore values.
- Store a salted PBKDF2 password hash in Android private storage. Set this phone's user-requested credentials during installation; do not commit credentials. Add native credential setup/reset.
- Require HTTP Basic authentication for assets and every API. Add same-origin/custom-header checks for mutations, numeric/localhost Host validation, bounded request bodies, no-store responses and restrictive content policy. No MAVLink command API.
- Test authentication failures, valid login, configuration validation, stale revision conflicts, body limits, cross-origin rejection and runtime pause/apply restrictions before implementation.
- Refactor transport/video ownership into a separately cancellable runtime. Permit remote apply for telemetry/synthetic video; real-camera starts and restarts require visible Android interaction. Preserve HTTP during runtime pause.
- Serve a responsive static UI for telemetry status, settings, camera/USB capability diagnostics and saved-versus-active configuration. Keep web bind changes pending until full service restart.
- Run JVM tests, APK build, lint and browser checks. Install with the existing signing key, seed private credential hash, and test through ADB forwarding and the phone VPN address. Push verified changes and check CI.
