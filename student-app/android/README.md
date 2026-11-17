# Assignment Review for Android

A small native Android app (Kotlin, Android 7+) that opens the school's Assignment Review server.

## What it adds to the web app

- First launch: "Connect to your school". The user types the server address; the app checks `/api/health` before saving it. Admins can build the address in so nobody types it (see below).
- Camera and gallery for "photo of copy" answers (no camera permission needed; the system camera app takes the photo).
- Back button follows the web app's pages.
- Offline screen with "Try again" and "Change server".
- Only the school server opens inside the app; other links open in the browser.
- Release builds connect over HTTPS only. Debug builds also allow `http://` for testing on your own network.
- No cloud backup of the app's data (sign-in cookies and queued answers stay on the phone).

## Build

GitHub Actions builds it on every push (`.github/workflows/student-app.yml`) and publishes the APK on the `student-app-latest` release. Locally, with Android Studio or the Android SDK:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Build the server address in, so users skip the setup screen:

```bash
./gradlew assembleRelease -PserverUrl=https://app.yourschool.org
# or in CI: APP_SERVER_URL=https://app.yourschool.org
```

## Release signing (Google Play or wide distribution)

The debug APK uses a shared debug key, fine for a pilot. For a real release, create your own key once and keep it safe; you need the same key for every update:

```bash
keytool -genkeypair -keystore release.jks -alias assignmentreview -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # paste into the repository secret STUDENT_APP_KEYSTORE_B64
```

Add the secrets `STUDENT_APP_KEYSTORE_B64` and `STUDENT_APP_KEYSTORE_PASSWORD`. CI then also publishes a signed `assignment-review.apk`.
