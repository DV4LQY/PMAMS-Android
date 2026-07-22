# PMAMS Android app

This is a lightweight Android WebView client for the existing PMAMS Laravel system. It keeps the existing login, SPA navigation, equipment workflows, QR scanner, reports, camera input, and permissions in one mobile app while using the ICTU logo as the app icon and connection-error screen.

## Configure the server URL

The default URL targets the Android emulator and a Laravel server running on the development computer:

```text
http://10.0.2.2:8000/
```

For a physical phone, use the computer's LAN IP and make sure the phone and computer are on the same network:

```powershell
.\gradlew.bat assembleDebug -PbaseUrl=http://192.168.1.25/pms_systemv2/public/
```

Use an HTTPS URL for production deployments. Cleartext HTTP is enabled only so local Laragon/Laravel testing works.

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
