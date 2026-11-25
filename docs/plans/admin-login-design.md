# DroidPilot login and administration

Proposed design for the embedded Android web server.

## Experience

Opening the server shows a dedicated login page with username, password, a Sign in button and inline errors. Successful login opens /admin. The responsive admin interface provides navigation for Overview, MAVLink, Video, Network and power, and Diagnostics. It uses the existing local assets, readable labels and real runtime data, with clear loading, unavailable, saved and applied states. Phone and desktop browsers are supported.

Overview shows companion state, traffic and available runtime actions. Configuration sections retain all existing editable settings, revision conflict handling, explicit Save and Apply, and phone-only camera activation requirements. Diagnostics shows network, hardware and recent events. Logout is always available. Expired sessions return to login with an explanatory message.

## Authentication

Recommended approach: replace browser Basic authentication with server-managed sessions. Keep the existing Android-configured credential store. Public routes serve only the login page and its assets. POST /api/login verifies credentials and issues an opaque, cryptographically random HttpOnly SameSite=Strict session cookie. Store only a digest of each token in a bounded in-memory session store; expire sessions after eight hours and invalidate them on logout, credential changes or process restart. Never store passwords or tokens in browser localStorage or URLs.

Unauthenticated navigation to /admin redirects to login. Unauthenticated API requests return a structured 401 without a Basic challenge. Login errors do not reveal whether a username exists. Bound login bodies and verification concurrency, and throttle repeated failures. Preserve numeric-IP Host validation, no-store responses, content security policy, same-origin checks and custom headers for mutations, including login and logout. HTTP remains supported for the existing private-network server; cookie Secure is enabled only when transport is HTTPS.

Alternative: use a custom login form that retains Basic credentials in browser memory and attaches Authorization headers. This requires less server work, but makes logout and session expiry less reliable and keeps the password accessible to page scripts. Server-managed sessions are preferred.

## Verification

Add behavior tests for public login access, successful and failed sign-in, protected admin and API access, cookie attributes, expiry, logout, credential-change invalidation, cross-origin rejection and configuration saves. Run core/webserver tests, Android build and lint, plus JavaScript syntax checks. Inspect phone and desktop browser layouts and report device checks separately. Install and verify on the connected phone when available. Do not push without a current explicit request.

## Scope

Single administrator using the existing Android login configuration. No registration, cloud hosting, multi-user roles, vehicle commands or new video transport. Keep the server embedded in DroidPilot and preserve other uncommitted work.
