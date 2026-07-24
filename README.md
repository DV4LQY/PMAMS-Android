# PMAMS Android app

This is a lightweight Android WebView client for the existing PMAMS Laravel system. It keeps the existing login, SPA navigation, equipment workflows, QR scanner, reports, camera input, and permissions in one mobile app while using the ICTU logo as the app icon and connection-error screen.

## Configure the server URL

The default build tries the HTTPS local PMAMS server first:

```text
https://192.168.171.9/pmams/public
```

If the local server cannot be reached, the app automatically switches to the hosted PMAMS public entry point:

```text
https://pmams.catsu.edu.ph/pmams/public
```

The local URL always has priority. Once login or the dashboard finishes loading, connection monitoring stays active every 30 seconds. If the active endpoint fails, the app switches to the other URL after a 20-second timeout; while hosted is active, it also checks the local URL and switches back as soon as local responds. Both directions can switch without a cooldown. A popup notification shows the source and destination URLs, then the app automatically opens the swapped URL.

You can override either endpoint at build time. For a physical phone, use the computer's LAN IP and make sure the phone and computer are on the same network:

```powershell
.\gradlew.bat assembleDebug `
    -PbaseUrl=https://192.168.1.25/pms_systemv2/public/login `
    -PfallbackUrl=https://pmams.catsu.edu.ph/pmams/public
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
