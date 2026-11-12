# Verification — 2026-10-15

Release: PKUAutoBus 1.0.0 (`app.pkuautobus`, version code 2), minimum Android 8.0 / API 26, target API 36. This GitHub release uses a new signing key, so earlier local installs must be uninstalled once.

## Completed

- 19 JVM unit tests passed: district matching with the actual bundled Haidian/Changping boundaries, acceptance of coarse location, rejection of stale/invalid/outside locations, next departure and date boundaries, ten-minute expiry, server clock, exact-trip reconciliation, response parsing, pagination, QR payload fidelity, cancellation parameters, and rejection of changed or missing portal rules.
- 9 instrumented tests passed on the Android 17 / API 37 emulator: icon controls and license dialog; campus toggling without submitting bookings; reset to location selection when reopening; centered QR display and decoding; activity recreation and encrypted persistence; cancellation confirmation; offline QR retention and background polling suspension; used-code removal; uncertain cancellation after restart; uncertain booking across reauthentication without resubmission; expiry; and location arriving during the initial network request.
- After removing the intermediate information dialog, the campus/icon-controls test was rerun and passed. It verifies that the information icon opens the licenses directly and that the old confirmation button is absent.
- Release APK compiled and signed with the new key; after uninstalling the old package, it installed and launched on the API 37 emulator. `apksigner verify --verbose --print-certs` passed with APK Signature Scheme v2. Certificate SHA-256: `112bf564958c36633fdfc17adde8a9c4bc2cd7b47462a34a45e209f8e184c941`.
- Release lint completed with zero errors and 14 warnings: tool/dependency update suggestions, target API update suggestion, and Kotlin convenience-extension suggestions. Pinned versions remain unchanged.
- Booking, boarding QR, cancellation-dialog, and license screenshots were inspected. Current images in `dist/previews` use fake portal data; the boarding QR is clearly labelled and cannot be used to board. The older images at these paths have been replaced with the current UI.
- On 2026-11-12 the boarding screen was simplified: the passenger and expiry rows were removed, and the cancel action became a button. All 19 unit tests and 9 instrumented tests were rerun and passed, and the boarding/cancellation screenshots were refreshed.
- Portal routes, read endpoints, and website request formats were inspected using authenticated, read-only access. Test writes go only to the fake portal.

## Remaining real-device checks

Sign into PKU inside the installed app; verify approximate location on a physical phone in both districts; make one intended real reservation; compare its QR and cancellation state against the official portal; and confirm the driver can scan it. These checks were not performed. Location tests use fixture coordinates and model updates, rather than a real phone's location provider. No real booking, cancellation, or driver scan was used for testing.

District matching runs on-device using WGS84 boundaries. It accepts fixes up to five minutes old with accuracy up to 5 km; a coarse fix near a district boundary can choose the adjacent district and should be corrected with the campus-name toggle. Points outside both districts leave manual selection available.

The portal has no documented public API. Its fields, login policy, and rules may change. The app rejects unsupported flows and provides the official portal as a fallback. The departure-plus-ten-minutes cutoff is the requested app rule; server-reported usage or cancellation takes priority. A cached offline QR cannot guarantee server acceptance.

## Reproduce

```powershell
.\gradlew.bat testDebugUnitTest lintDebug connectedDebugAndroidTest
.\build-release.ps1
```

Instrumented tests were run through ADB against the fake portal. Generated reports and release artifacts are not committed; the UI screenshots are committed under `dist/previews`.

Signing material is outside the project in `%LOCALAPPDATA%\PKUAutoBus\signing`; it must be kept private for future updates. Release APKs are distributed through GitHub Releases.
