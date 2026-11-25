# Web configuration

DroidPilot 0.2 adds an authenticated embedded HTTP server. Default bind address is `0.0.0.0`, port `4001`. No cloud service is used. The server belongs to the Android foreground service, not the Activity.

## Start and login

1. In Android Network settings, set a web username and password with **Save web login**. Credentials are separate from companion configuration. There is no shared hard-coded password in the APK. The password is stored as a salted PBKDF2-HMAC-SHA256 hash in private app storage.
2. Enable web configuration and set the listening address/port. Start the companion service from the visible Activity.
3. On the phone open `http://127.0.0.1:4001`. From another computer use `http://PHONE_IP:4001` with the phone's Wi-Fi, Ethernet or VPN address.
4. A dedicated login page appears. Enter the web username and password to open `/admin`. Incorrect credentials produce an inline error. If login needs resetting, use **Save web login** in Android Network settings.
5. Navigate Overview, MAVLink, Video, Network & power, and Diagnostics. Overview shows live runtime and telemetry status; settings sections retain explicit Save and Apply.

Authentication uses an opaque session cookie (HttpOnly, SameSite=Strict, eight-hour lifetime). Server memory stores only token digests, bounded to 64 sessions. Sessions expire or become invalid after logout, credential replacement, or process restart. Unauthenticated navigation redirects to login; API requests return JSON 401 without a browser Basic-auth prompt. Encoded URL segments and repeated slashes cannot skip authentication: only exact public login paths are exempt. Repeated login attempts are globally throttled and password verification concurrency is bounded.

Use a trusted private network or encrypted VPN. The embedded server uses HTTP without TLS; the login password and session cookie are not encrypted by HTTP. All browser resources are served locally from the app. The page has no vehicle command injection endpoint.

## Save, apply and pause

Edit MAVLink, video, power and web settings. **Save settings** validates and persists them. **Apply saved settings** restarts the companion runtime using the saved values. Web listener changes apply only after **Stop service** and **Start** in Android; the existing page remains available until then.

A configuration revision prevents stale browser or Android edits from silently overwriting another client's changes. If saving returns a conflict, reload the settings and edit again.

**Pause companion** closes MAVLink/video resources but leaves HTTP available. Apply can start telemetry or synthetic video again. Real camera activation/restart requires visible Android interaction: pause remotely, then use Start in the Android dashboard and grant any required permission. The web API does not bypass Android camera restrictions. The native notification's Stop action ends the entire service, including HTTP.

The browser exposes configuration and diagnostics, not vehicle steering, arming, gamepad input, live browser video or WebRTC signaling. Those remain future work. Status uses bounded HTTP polling, not WebSockets.

## API

Configuration and runtime paths require a valid session cookie. Mutation requests also need `X-DroidPilot-Request: 1`. Browser Origin must match the request's HTTP Host. Use a numeric phone IP or localhost; other Host names are rejected.

| Method and path | Result |
| --- | --- |
| POST `/api/login` | Verify JSON username/password and issue a session cookie (public) |
| POST `/api/logout` | Revoke the current session and clear its cookie |
| GET `/api/config` | Saved string-valued configuration map and revision |
| PUT `/api/config` | Save JSON with `config` and `revision`; 409 on stale revision |
| GET `/api/status` | Runtime/traffic diagnostics, network, capabilities and bounded recent logs |
| POST `/api/runtime/apply` | Apply saved telemetry/synthetic-video settings; 409 when native camera interaction is required |
| POST `/api/runtime/pause` | Pause companion, keep HTTP available |

Configuration bodies are limited to 16 KiB with a five-second read timeout. Assets use no-store responses and a restrictive same-origin content policy. There is no generic MAVLink injection route.

## Verification

Automated JVM tests cover credential hashing, login success/failure, public login assets, authenticated administration and APIs, missing credentials, malformed and oversized login requests, cookie flags, logout, credential changes, expiry, session capacity, login throttling, encoded/repeated-slash authentication regressions, configuration validation/revisions and runtime mutation protection. JavaScript tests cover locking settings while saving and unlocking after failure.

On 2026-11-25 the debug APK was installed on the attached Android phone. USB-forwarded HTTP checks confirmed the login page, successful login with existing configured credentials, authenticated admin/config/status, logout, and 401 after logout. The user reported that the page worked. The authentication correction passed regression tests, was installed, and was checked on the phone: encoded and repeated-slash admin/API URLs returned 401 without a session. Visual browser automation was unavailable; desktop/mobile layout acceptance and full MAVLink/SITL integration remain separate manual checks in `tests/MANUAL.md`.
