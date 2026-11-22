# Billy

Billy is an Android app for splitting bills. Right now it supports:

- **Phone-number sign-in.** The app texts a one-time sign-in link; tapping it opens the app and signs you in.
- **User profiles.** After first sign-in you set up a profile: first name, optional last name, optional photo, city and state (filled in from your approximate location, or typed by hand), and an optional bio. You can edit it later.

The repo has two parts:

| Path | What it is |
| --- | --- |
| `app/` | Android app (Kotlin, Jetpack Compose) |
| `backend/` | Small Node.js service for sign-in and profiles. It uses SQLite and has no npm dependencies |

## Prerequisites

- **Android Studio** (recent), with Android SDK platform 37 installed. The app runs on Android 7.0 (API 24) and newer.
- **JDK 21** for Gradle. Android Studio's bundled JDK works. On the command line:
  ```sh
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS
  ```
- **Node.js 22.13 or later** for the backend. It uses the built-in `node:sqlite`, so there's no `npm install` step.
- An **Android emulator** for local development. Debug builds connect to the backend through the emulator's host address, `10.0.2.2`.

## 1. Run the backend

```sh
cd backend
cp .env.example .env
npm start
```

The server listens on `http://localhost:3000`, with the API under `/api`.

**Twilio is optional for development.** If the Twilio settings in `.env` are left blank, the backend doesn't send real texts. It prints the sign-in link to the server console instead.

To send real texts, fill in `TWILIO_ACCOUNT_SID` and `TWILIO_AUTH_TOKEN` in `.env`, plus either `TWILIO_FROM` or `TWILIO_MESSAGING_SERVICE_SID`.

> **Keep credentials in `backend/.env` only.** That file is gitignored. `.env.example` is committed, so it must contain placeholders only.

All settings are documented in [`backend/.env.example`](backend/.env.example).

## 2. Run the app

With the backend running and an emulator started:

```sh
./gradlew :app:installDebug
```

Or open the project in Android Studio and run the `app` configuration.

Debug builds talk to `http://10.0.2.2:3000/api`. Plain HTTP is allowed only to that address, and only in debug builds.

## 3. Sign in locally

1. In the app, enter any valid phone number and tap **Text me a sign-in link**.
2. Copy the link the backend printed to its console. It looks like `https://billy.example.com/signin?token=...`.
3. Open it in the app:
   ```sh
   adb shell am start -a android.intent.action.VIEW -d "<link>" com.example.billy
   ```
   Pass the package name (`com.example.billy`) explicitly. The link's domain isn't verified for the app locally, so without it Android would open the link in a browser.
4. You'll land on profile setup. To test location detection, set a location in the emulator's **Extended controls → Location** before allowing the permission. To test manual entry, deny the permission and type a city and state.

Reinstalling or clearing app data signs you out. Profiles are stored in `backend/billy.db`; delete that file to start fresh.

## Tests

```sh
# Backend
cd backend && npm test

# Android unit tests
./gradlew :app:testDebugUnitTest

# Android instrumented tests (needs a running emulator or device)
./gradlew :app:connectedDebugAndroidTest

# Lint
./gradlew :app:lint
```

## Deploying to a real server

Release builds still point at the placeholder host `billy.example.com`. To run against a real server:

1. Host the backend behind HTTPS. If it's behind a reverse proxy that sets `X-Forwarded-For`, set `TRUST_PROXY=1`.
2. These values must match between the app and backend:

   | Backend (`.env`) | App (`app/build.gradle.kts`) |
   | --- | --- |
   | `AUTH_HOST` | `manifestPlaceholders["authHost"]` |
   | `BASE_PATH` | path part of `API_BASE_URL` |
   | `ANDROID_PACKAGE` | `applicationId` |

3. Set `ANDROID_SHA256_CERT_FINGERPRINT` to your signing certificate's SHA-256 fingerprint. The backend then serves `/.well-known/assetlinks.json`, which lets Android open the sign-in links straight in the app.

## Privacy notes

- Profiles hold only what bill splitting needs. City and state are visible to people you split bills with, the same as your name and photo.
- Location uses approximate (coarse) permission only. Coordinates are used once to look up your city and are never stored or sent.
- Photos are cropped to a 512 px square and re-encoded before upload, which removes hidden metadata such as where the photo was taken.
- On the device, the session token is encrypted with a key that can't leave the device, and it's excluded from backups.
- The backend stores only SHA-256 hashes of sign-in links and session tokens.
