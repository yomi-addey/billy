# Billy

## Rules
- **Never commit or push without the user's explicit permission.**
- **Before any commit, check for leaked API keys and authentication credentials.** This covers Twilio SID/token/from-number, `backend/.env`, `backend/.env.example`, `backend/*.db`, signing keys/keystores, and `local.properties`. Never put secret values in code, docs, or logs.

## Overview
Billy is an Android app (Kotlin, Jetpack Compose) for billing / bill splitting. So far it has phone-number sign-in and user profiles. After first sign-in the user creates a profile (first name, optional last name, optional photo, city + state auto-detected with manual override, optional bio); after that the app shows a placeholder home screen with the profile and an Edit profile button.

- `app/` is the only Gradle module (`:app`, package `com.example.billy`).
- `backend/` is a small dependency-free Node service that handles SMS-link sign-in and profiles.

## Commands
Android (run from the repo root; Gradle needs JDK 21):
- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Instrumented tests: `./gradlew :app:connectedDebugAndroidTest`
- Lint: `./gradlew :app:lint`
- Install on device/emulator: `./gradlew :app:installDebug`

Backend (needs Node 22.13 or later; no `npm install` step):
- Run: `cd backend && npm start` (loads `backend/.env` if present)
- Test: `cd backend && npm test` (`node --test test/*.test.js`)

## Android architecture
Source is under `app/src/main/java/com/example/billy/`:
- `MainActivity.kt`: `singleTop`. It builds the repositories and view models, picks the screen (session check → login → profile gate), and handles the `/signin?token=` deep link in `onCreate` and `onNewIntent`.
- `view_models/LoginScreenViewModel.kt`: `LoginUiState` exposed as a `StateFlow`, plus a `Factory`. Reads the stored session asynchronously (`isCheckingSession`).
- `view_models/ProfileGateViewModel.kt`: after sign-in, loads `GET /profile` and decides between setup (404), home, or error. Owns edit mode and `formSession` (the key for the form's view model, bumped so a new form never reuses stale input). A 401 sets `sessionExpired`, which signs out.
- `view_models/ProfileSetupViewModel.kt`: the profile form for both creation and editing (`existing != null`). Auto-detected location never overwrites typed values; tapping "Use my location" does.
- `data/SecureTokenStore.kt`: `TokenStore` and `KeystoreTokenStore`. The session token is AES-256-GCM encrypted with a non-exportable Android Keystore key and stored in Preferences DataStore `secure_auth`. Undecryptable values read as signed out. It migrates and deletes the old plaintext `SharedPreferences("auth")`. Both files are excluded from backup and device transfer.
- `data/Http.kt`: shared `httpRequest` (`HttpURLConnection`) and `AuthException(httpCode)`.
- `data/AuthRepository.kt`: `AuthRepository` and `HttpAuthRepository(TokenStore)`.
- `data/ProfileRepository.kt`: `Profile`, `ProfileLimits` (which must match the backend validation), `ProfileRepository` and `HttpProfileRepository`.
- `data/LocationProvider.kt`: `AndroidLocationProvider` uses `LocationManager` with only `ACCESS_COARSE_LOCATION`, then `Geocoder` to get city and state. Coordinates are never stored or sent.
- `data/Images.kt`: `prepareProfilePhoto` produces a center-cropped 512 px JPEG. Re-encoding strips EXIF, including GPS.
- `data/PhoneNumbers.kt`: country list, validation, E.164 formatting.
- `ui/views/` holds stateless composables. `ui/theme/` holds the theme.

Conventions: immutable `UiState` with `StateFlow`, stateless composables that take state and callbacks, and dependencies passed by hand through `ViewModelProvider.Factory` (no DI framework). The package is `view_models` (snake_case); keep that.

## Auth flow
1. App sends `POST /auth/sms-link` with `{phone}` in E.164.
2. Backend texts `https://{AUTH_HOST}/signin?token=...`.
3. The link opens the app through an Android App Link (`autoVerify`).
4. App sends `POST /auth/verify` with `{token}` and receives `{sessionToken, expiresAt}`.

The backend also has `GET /auth/session`, `POST /auth/logout`, `GET /signin` (fallback HTML page), and `/.well-known/assetlinks.json` (served only when `ANDROID_SHA256_CERT_FINGERPRINT` is set).

## Profiles
These routes need `Authorization: Bearer <session>` and return 401 without it. Profiles are keyed by the session's phone number.
- `GET /profile` returns `{firstName, lastName, city, region, bio, hasPhoto, photoUpdatedAt}`, or 404 if the user hasn't created one yet (that's how the app detects first-time setup).
- `PUT /profile` creates or updates the profile. Validation: `firstName` 1–50, `lastName` 0–50, `city`/`region` 1–80, `bio` ≤ 280; no control characters (the bio may contain newlines); unknown keys are ignored.
- `PUT /profile/photo` takes a raw `image/jpeg` body (≤ 1 MB, JPEG magic bytes checked). `GET /profile/photo` returns the image and `DELETE /profile/photo` removes it. Photo routes return 404 until a profile exists.
- Privacy: collect nothing beyond these fields. Location is city + state only, and "region" is labeled "State" in the UI.

## Backend architecture
`backend/src/`: `index.js` (startup and shutdown), `config.js` (`loadConfig`), `app.js` (`createApp(config, {db, sms, now})`, with `requireSession` for authenticated routes), `db.js` (SQLite in WAL mode; the `profiles` table stores photos as BLOBs), `sms.js` (Twilio, or prints to the console when no credentials are set). `test/auth.test.js` and `test/profile.test.js` run the real HTTP server on port 0 with an in-memory DB, a fake SMS sender and a fake clock.

Security design to preserve: only SHA-256 hashes of tokens are stored, login tokens are single-use, a newer link supersedes older ones, `/auth/sms-link` always returns 204 for well-formed requests, rate limits apply per phone and per IP, and request bodies are capped (4 KB for JSON, 1 MB for photos).

Env var names (values live in the untracked `backend/.env`): `PORT`, `AUTH_HOST`, `BASE_PATH`, `DB_PATH`, `LOGIN_TOKEN_TTL_MINUTES`, `SESSION_TTL_DAYS`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`, `TWILIO_MESSAGING_SERVICE_SID`, `TRUST_PROXY`, `ANDROID_PACKAGE`, `ANDROID_SHA256_CERT_FINGERPRINT`.

## Values that must match across app and backend
- `AUTH_HOST` = the `authHost` manifest placeholder in `app/build.gradle.kts`
- `BASE_PATH` = the path part of `BuildConfig.API_BASE_URL`
- `ANDROID_PACKAGE` = the app's `applicationId`

## Local development
Debug builds point `API_BASE_URL` at `http://10.0.2.2:3000/api` (the host machine as seen from the emulator). Cleartext HTTP is allowed only for `10.0.2.2`, and only in debug builds (`app/src/debug/`). Release still uses the `billy.example.com` placeholder.

1. `cd backend && npm start`. With no Twilio credentials, the sign-in link is printed to the console.
2. `./gradlew :app:installDebug`, then enter a phone number in the app.
3. Open the printed link in the app: `adb shell am start -a android.intent.action.VIEW -d "<link>" com.example.billy`. The `authHost` App Link isn't verified locally, so name the package explicitly.
4. To test location detection, set the emulator's location in Extended controls → Location.

## Known gaps
- Release host values are still placeholders (`billy.example.com`).
- `backend/.env.example` holds real credentials locally, so it must stay gitignored. Never commit it or copy its values anywhere. It is also missing `TWILIO_MESSAGING_SERVICE_SID`.
- The app never calls `/auth/session` or `/auth/logout`. `signOut` only clears local storage. An expired session is noticed when `/profile` returns 401.
- Profile photos aren't moderated, and they're stored as SQLite BLOBs (fine at current scale).
- Test coverage: view-model unit tests (`ProfileSetupViewModelTest`, `ProfileGateViewModelTest`/`LoginScreenViewModelTest`, which use the fakes in `Fakes.kt`), `PhoneNumbersTest`, the instrumented `KeystoreTokenStoreTest`, and the backend tests. The HTTP repositories and the UI are untested. `ExampleUnitTest` and `ExampleInstrumentedTest` are template stubs.
- No lint/format tooling (ktlint, detekt) and no CI.
- `README.md` is empty.
