# PMAMS Android app

This is a lightweight Android WebView client for the existing PMAMS Laravel system. It keeps the existing login, SPA navigation, equipment workflows, QR scanner, reports, camera input, and permissions in one mobile app while using the ICTU logo as the app icon and connection-error screen.

## Configure the server URL

The default build tries the HTTPS local PMAMS server first:

```text
https://192.168.171.9/pmams/public
```

If the local server cannot be reached, the app offers the hosted PMAMS login as the alternate connection:

```text
https://pmams.catsu.edu.ph/login
```

The local URL always has priority. After the portal loads, the app checks the local endpoint and active endpoint every two minutes. If another network is detected or the active URL becomes unavailable, the app asks whether to switch networks or continue; it never changes networks without confirmation. If the initial local or hosted load has not responded after 60 seconds, it also asks whether to continue trying the current connection or switch to the other URL.

You can override either endpoint at build time. For a physical phone, use the computer's LAN IP and make sure the phone and computer are on the same network:

```powershell
.\gradlew.bat assembleDebug `
    -PbaseUrl=https://192.168.1.25/pms_systemv2/public/login `
    -PfallbackUrl=https://pmams.catsu.edu.ph/login
```

The app upgrades configured PMAMS `http://` links to `https://`, blocks cleartext traffic, and proceeds past SSL certificate warnings for the configured PMAMS hosts. Use a valid HTTPS certificate when possible; the bypass is intended for private/self-signed PMAMS deployments.

## Build the APK

Open `android-app` in Android Studio and run **Build > Build APK(s)**, or use Gradle from that folder:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Version control

The version is defined in `gradle.properties` with `appVersionCode` and `appVersionName`.
Increase the code for every published build and update the human-readable name as needed:

```powershell
.\gradlew.bat assembleDebug '-PappVersionCode=2' '-PappVersionName=1.1.0'
```

The version name is also shown on the app connection-error screen. Release builds should
use the same properties together with the project's signing configuration.

The wrapper includes native WebView handling for JavaScript, cookies, back navigation, external links, file uploads, equipment photos, QR camera permission, and downloads.
