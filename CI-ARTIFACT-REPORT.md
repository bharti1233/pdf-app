# CI Artifact Delivery Report

## Previous Behavior
`.github/workflows/test.yml` has two APK-related jobs:

- **PlayStore Release Build Check** ran `:app:assemblePlaystoreRelease --continue` and already had an upload-artifact step named `playstore-release-test-apk` pointing at `app/build/outputs/apk/playstore/release/*.apk` with `if: always()`.
- Unit tests upload `unit-test-results`.
- No job failed; however the artifact was also uploaded with a non-obvious name and `if-no-files-found: warn`, so a successful-looking run could carry an empty/missing APK artifact without anyone noticing.

## Root Cause
Artifact delivery worked, but:
- the upload ran with `if: always()`, so a partially created output directory could upload nothing and CIs would not fail;
- the artifact name (`playstore-release-test-apk`) did not identify the app or flavor;
- the CI step could not fail when the APK was absent (`if-no-files-found: warn` + `ls ... || echo`);
- on a manual `git checkout "$TAG" -- gradle.properties` path in the older workflow, release signing secrets were missing, producing an unsigned APK whose status was easy to miss.

## Workflow Change
File: `.github/workflows/test.yml`

- Removed `if: always()` from the APK upload and the `|| echo "No APKs found"` swallow.
- Added an explicit verification step:
  - computes `APK_DIR=app/build/outputs/apk/playstore/release`;
  - fails if the directory is missing;
  - fails if no `*.apk` in that directory;
  - prints APK path, filename, and size in bytes.
- Renamed the artifact to `pdf-toolkit-playstore-apk` and changed `if-no-files-found` to `error`.

## Actual APK Path
Verified from the successful CI run's "Checking for APK output..." step:

```
app/build/outputs/apk/playstore/release/pdftoolkit-playstore-v1.3.225.apk
```

(55 MB on disk; filename comes from `applicationVariants` rename in `app/build.gradle.kts`: `pdftoolkit-${flavorName}-v${versionName}.apk`.)

## Signing Status
In this CI run the workflow log printed:

```
ERROR: Keystore file NOT found at: /home/runner/work/pdf-app/pdf-app/app/keystore.jks
No keystore found or F-Droid build - skipping signing
```

Verified against the downloaded APK: there are no `META-INF/*.SF`/`*.RSA`/`*.DSA`/`*.EC` entries — the produced release APK is **UNSIGNED** unless the release build runs with the signing secrets present (as `deploy.yml` does).

## Artifact Name
`pdf-toolkit-playstore-apk`

(Artifacts also still contain `unit-test-results` and `lint-report`.)

## Verification
- Workflow YAML parses (`python3 yaml.safe_load` OK).
- Download of the artifact from the latest green run succeeded and, after extraction, contains exactly one entry:

```
pdftoolkit-playstore-v1.3.225.apk   56,879,934 bytes
```

- `unzip -t` reports no errors; the APK contains `AndroidManifest.xml` and 2991 entries as a valid zip/APK. Not installed locally (no target device required).

## GitHub Actions Result
Latest successful run:
- HEAD: commit `9ca4a92d` then `CI: upload PlayStore APK...` commit after that (the rename commit);
- Static Analysis: PASS
- Build Verification: PASS (`assembleFdroidDebug`, `assemblePlaystoreDebug`)
- Unit Tests: PASS
- Crash Pattern Check: PASS
- PlayStore Release Build Check: PASS (`assemblePlaystoreRelease`)

Artifacts after that run: `unit-test-results`, `lint-report`, `pdf-toolkit-playstore-apk`.

## Remaining Issues
- APK uploaded from CI test workflow is **unsigned** (no signing secret in this environment). For a signed release use the `deploy.yml` pipeline, which decodes the keystore and sets `CI=true` with the real signing secrets.
- The same `playstore-release-test-apk` name from earlier runs is still present in older run history; those runs are unrelated to the new artifact name.
- No application code, dependencies, signing logic, or manifest was modified during this fix.

**Final status: PASS**
