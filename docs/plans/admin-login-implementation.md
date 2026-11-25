# Admin login implementation plan

**Goal:** Add public form login and a protected responsive admin dashboard to the embedded server.
**Architecture:** Keep existing configuration/runtime backends; replace Basic authentication with bounded in-memory sessions and a dedicated login route. Serve local HTML/CSS/JS without external dependencies.
**Tech stack:** Kotlin, Ktor CIO, vanilla JavaScript and CSS, Gradle/JUnit.

- [x] Add failing HTTP behavior tests in WebServerTest.kt for public login, protected admin/API, login errors, logout, same-origin mutations and credential invalidation. Add clock-driven session expiry/bounds tests in WebSessionsTest.kt.
- [x] Run `./gradlew :webserver:test` and confirm expected failures.
- [x] Implement WebSessions.kt with random tokens, SHA-256 token digests, monotonic eight-hour expiry, credential fingerprints and at most 64 sessions. Add bounded global login throttling and fail-fast verification concurrency.
- [x] Update WebServer.kt with public /login assets, POST /api/login, authenticated /admin, POST /api/logout, structured API 401s, strict HttpOnly cookies and origin checks. Preserve configuration validation and existing runtime protections.
- [x] Replace web assets with responsive login and admin navigation, human-readable status, full configuration editing, expiry handling and logout. Preserve unsaved edits during polling.
- [x] Run web/core tests, Android assemble/lint, JS syntax and browser checks; inspect desktop and narrow layouts. Document actual results in docs/WEB.md and tests/MANUAL.md.
- [x] Install on the attached phone if available; check endpoint authentication and service availability. Leave changes local without pushing.

Browser visual checks could not run because the browser host was unavailable. Device HTTP checks and user confirmation are recorded in docs/WEB.md; SITL integration is being guided separately.
