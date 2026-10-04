# Package Migration Report

## Old package
`com.yourname.pdftoolkit`

## New package
`com.hmx.toolkit`

## applicationId
- Updated in `app/build.gradle.kts` to `applicationId = "com.hmx.toolkit"`
- `applicationIdSuffix = ".debug"` preserved for `debug` builds
- No new suffix was added to other flavors

## namespace
- Updated in `app/build.gradle.kts` to `namespace = "com.hmx.toolkit"`

## Kotlin/Java packages
- All `package com.yourname.pdftoolkit*` declarations replaced by `com.hmx.toolkit*`
- All imports `com.yourname.pdftoolkit*` replaced by `com.hmx.toolkit*`
- `BuildConfig` package is now `com.hmx.toolkit.BuildConfig`
- `R` package is now `com.hmx.toolkit`

## Source directories moved
Moved for every source set:
- `app/src/main/java/com/yourname/pdftoolkit/` → `app/src/main/java/com/hmx/toolkit/`
- `app/src/playstore/java/com/yourname/pdftoolkit/` → `app/src/playstore/java/com/hmx/toolkit/`
- `app/src/fdroid/java/com/yourname/pdftoolkit/` → `app/src/fdroid/java/com/hmx/toolkit/`
- `app/src/opensource/java/com/yourname/pdftoolkit/` → `app/src/opensource/java/com/hmx/toolkit/`
- `app/src/test/java/com/yourname/pdftoolkit/` → `app/src/test/java/com/hmx/toolkit/`
- `app/src/androidTest/java/com/yourname/pdftoolkit/` → `app/src/androidTest/java/com/hmx/toolkit/`

## Manifests changed
- `app/src/main/AndroidManifest.xml` keeps `android:authorities="${applicationId}.provider"` → resolves to `com.hmx.toolkit.provider`
- `app/src/playstore/AndroidManifest.xml` keeps only `<uses-permission android:name="android.permission.INTERNET"/>`

## FileProvider
- FileProvider is configured as `${applicationId}.provider`, so it now resolves to `com.hmx.toolkit.provider`

## Deep links / intents
- No custom deep-link authority used
- Manifest intent filters based on MIME types only

## Tests migrated
- `test` and `androidTest` packages/directories moved and imports updated
- Tests that had collisions with deleted helpers may need validation in CI

## R8 / ProGuard
- `app/proguard-rules.pro` replaced with `com.hmx.toolkit` where referenced

## CI checked
- `.github/workflows/deploy.yml` Indus endpoint URL updated to `/com.hmx.toolkit`
- No other CI reference requires change

## External services requiring manual changes
- `https://developer-api.indusappstore.com/devtools/aab/upgrade/com.yourname.pdftoolkit` in `deploy.yml` → now `com.hmx.toolkit`
- Indus / Play developer-console registrations, OAuth clients, or SDK configs outside repository text likely need manual updates to match the new package

## Remaining old-package references
- `AUDIT_REPORT.md`, `DEBUG-APK-REPORT.md`, `PHASE-1-REPORT.md`, `CI-ARTIFACT-REPORT.md`, `docs/reports/*`, `docs/saf-crash-hardening-audit-and-fix.md`: intentionally left as historical texts
- All functional/active references were migrated

## Build / tests / APK verification
- **Local builds/tests were NOT executed** per user instruction due to limited device RAM / environment limits
- Source-level grep for old package is clean after migration
- CI should now build variants containing `com.hmx.toolkit`
- Any old install of `com.yourname.pdftoolkit` will be treated as a different app from `com.hmx.toolkit`

## Unrelated changes / note
- No signing configuration changes were made
- No dependencies were added or removed
- Debug APK install check was not tested
