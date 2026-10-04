# Debug APK Distribution Report

## Exact Gradle Task
`./gradlew :app:assembleOpensourceDebug`

CI job: `Debug APK Build (installable, debug-signed)` in `.github/workflows/test.yml`.

## APK Path
CI: `app/build/outputs/apk/opensource/debug/app-opensource-debug.apk`

## Package Name
`com.yourname.pdftoolkit.debug` (applyed `applicationIdSuffix = ".debug"` for debug builds).

## Version
From `gradle.properties`: `APP_VERSION_CODE=225`, `APP_VERSION_NAME=1.3.225` + suffix `-debug`.

## Signing Scheme
Android built-in **v2 (APK Signature Scheme v2)** signature.
Signer: `C=US, O=Android, CN=Android Debug`, RSA 2048.
SHA-256 of certificate: `859af49a1035777d558123c9bbf1603283a9cc4f4bb4e0c23e45ce08ccc3adff`.

## Artifact Name
`pdf-toolkit-debug-apk`

## Verification Result
```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme: false
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
```
`unzip -t`: no errors detected.

## CI Result
- Static Analysis: PASS
- Build Verification: PASS
- Unit Tests: FAIL (pre-existing flaky `PdfCompressorTest > testCompressPdfToTargetSize_Failure` — not caused by this change; same flaky set observed across previous run)
- Crash Pattern Check: PASS
- Debug APK Build: PASS (artifact `pdf-toolkit-debug-apk` uploaded, ~50 MB)
- Release APK Build (production signing): SKIPPED (`if: false` — intentionally disabled)

## ADB Install
NOT TESTED — no device/emulator available in this environment.

## Notes
- No production keystore, password, key alias, or third-party upload involved.
- Production signing config (`signingConfigs.release`) is untouched; the release job is retained but guarded with `if: false`.
- Temporary-only artifact: for direct install on device, use `app-opensource-debug.apk`. Do not treat it as a production release.
