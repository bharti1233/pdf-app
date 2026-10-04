# PDF Toolkit — Phase 1 Security & Correctness Report

## 1. Changes Implemented

Nine Phase-1 objectives. Details in sections 2–15. No UI redesign, no architecture refactor, no dependency upgrades, no file deletions other than dead `util/PdfTools.kt`. Not a git repository in this environment (no `.git`), so `git status`/history is unavailable — changes were made as targeted edits only.

## 2. Release Signing Security

File: `.github/workflows/deploy.yml` (Indus upload step)
Before: `curl -F "file=@${AAB_FILE}" -F "file=@${KEYSTORE_FILE}" -F "keyPassword=..." -F "keystoreAlias=..." -F "keystorePassword=..."` to `developer-api.indusappstore.com`.
After: curl sends only `-F "file=@${AAB_FILE}"` (the already-signed AAB) plus the Authorization header. `KEYSTORE_FILE` variable and its `echo` removed. Comment added stating the invariant.
Reason: never transmit signing material to a third party.
Test/verification: grep of `deploy.yml` shows the only remaining keystore references are the on-runner CI decode/sign steps (lines 104–159). YAML parse OK.
Risk: Indus may reject AABs uploaded without the keystore fields it previously required — external behavior, verify on next deploy.

File: `app/build.gradle.kts` (signingConfig CI block)
Before: printed `KEY_ALIAS value: '...'` and per-secret presence lines.
After: only per-secret `present: true/false` booleans remain; alias value no longer printed.
Reason: remove secret-value logging from build logs.
Verification: grep.
Risk: none.

Key rotation: NOT performed (no safe procedure in repo; would invalidate Play signing). Marked as a follow-up manual action.

## 3. Redaction

File: `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfRedactor.kt`
Before: visual overlay rectangles appended to the content stream; `flattenAfterRedaction=true` was an empty `if` block; underlying text/images remained extractable; KDoc admitted "visual redaction".
After: `redactAreas` now rasterizes each affected page with `PDFRenderer.renderImageWithDPI(..., 200, ImageType.RGB)`, paints the redaction boxes into the bitmap, and replaces the page in a new `PDDocument` (unaffected pages are imported via `importPage`). Original page content is discarded for affected pages. The `flattenAfterRedaction` parameter was removed (no callers existed in the repo), so the operation can no longer silently claim secure behavior without doing it. `redactText` still fails explicitly with `UnsupportedOperationException`. New pages are added via `PDPage`/`LosslessFactory`/`PDPageContentStream.drawImage`. `bitmap.recycle()` in `finally`, both documents closed in `finally`. No temporary files are created (in-memory rasterization), so cleanup is structural. Failure returns `Result.failure` and does not produce a "successful" output file.
Reason: secure redaction — redacted text is not extractable and the overlay cannot be removed to recover content.
Test/verification: new `PdfRedactorTest.kt` covers: text under redaction not extractable (PDFTextStripper), image/mixed case (rasterized page), multiple regions on one page, multiple pages, unaffected page text preserved, output reopens with same page count, malformed input → `Result.failure`, success and failure leave `cacheDir` unchanged.
Risk: redacted pages lose text selectability/searchability — deliberate, documented in KDoc. Rasterization at 200 DPI may enlarge files on heavily redacted documents; malformed rectangle coordinates are clamped by drawRect semantics (no explicit clamp added — UNVERIFIED edge: fully off-page rect still draws a black box outside the visible crop area).

UI: deliberately NOT wired into any screen (no callers existed; audit confirmed). Redaction is safe to wire now, but UI wiring is a later decision.

## 4. Visual Signature

Files: `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfSigner.kt`, `app/src/main/res/values/strings.xml`, `README.md`, `metadata/com.yourname.pdftoolkit.yml`, `fastlane/metadata/android/en-US/full_description.txt`
Before: KDoc said "digital signatures (visual representation)"; README/metadata/Play listing said "Add digital signatures to documents". UI title "Sign PDF".
After: KDoc documents "NOT a cryptographic digital signature … does not provide authentication, integrity, or legal non-repudiation". `sign_title`, `tool_sign_pdf`, `desc_sign`, `desc_sign_pdf`, `sign_no_pdf_subtitle` updated to "Visual Signature"/accurate wording. README/metadata/full_description updated.
Reason: honest feature description, no cryptographic claim.
Test: `PdfSignerTest.kt` asserts the output opens, page count preserved, and the string contract (`sign_title`/`tool_sign_pdf`) contains "Visual" rather than any crypto claim.
No cryptographic signing implemented (out of scope).

## 5. URL → PDF

Files: `app/src/playstore/AndroidManifest.xml` (new), `app/build.gradle.kts`
Before: main manifest declared no `INTERNET`; `HAS_NETWORK_URL_TO_PDF=true` only for playstore; URL→PDF relied on an implicit/transitive permission.
After: `app/src/playstore/AndroidManifest.xml` declares `<uses-permission android:name="android.permission.INTERNET"/>` with a comment explaining the mapping. F-Droid and OpenSource builds keep `HAS_NETWORK_URL_TO_PDF=false` and have no flavor manifest, so they remain offline (no explicit `INTERNET`). Play flavor gradle block now documents the mapping.
Reason: every variant's network capability matches its manifest and its flags.
Test: `FlavorGatingTest.kt` asserts `HAS_NETWORK_URL_TO_PDF == (FLAVOR == "playstore")`, `USE_MLKIT_OCR == (FLAVOR == "playstore")`, `HAS_OCR == true` — runs under each flavor's unit-test task.
Verification gap: final merged manifests were NOT verified by an actual Gradle `processManifest` run (no SDK here). See §10/§14.

## 6. OCR Confidence

Files: `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfOcrProcessor.kt`, `app/src/fdroid/.../OcrEngine.kt`, `app/src/opensource/.../OcrEngine.kt`
Before: `OcrPageResult.confidence = 0.85f` unconditionally; Tesseract engine emitted raw 0–100 word confidences into a 0–1 field (silent scale bug).
After: Tesseract word confidence normalized to 0–1 (`/100f`) in both FOSS OcrEngine files. Page confidence = mean of per-word confidences via `PdfOcrProcessor.averageWordConfidence(...)` (empty list → 0f). ML Kit pages with no per-word confidence honestly report 0 rather than a placeholder.
Test: `OcrConfidenceTest.kt` asserts the mean, asserts `!= 0.85f` for a zero-confidence input, and asserts 0 for unavailable/empty input.
Risk (ponytail: ceiling): 0 conflates "engine provided no confidence" (ML Kit) with "low-quality OCR"; honest but lossy. Upgrade path if needed: make `confidence` nullable in the result model.

## 7. PdfTools / Temporary Files

File: `app/src/main/java/com/yourname/pdftoolkit/util/PdfTools.kt` — DELETED. Verification before deletion: grep for `PdfTools`/`flattenAndSavePdf` across `app/src` (main/test/androidTest) found only its own file and one unused import in `SettingsScreen.kt` (line 35), which was also removed. No reflection/test/flavor references (only a historical doc mention).
File: `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SettingsScreen.kt` — unused import removed.
Temp cleanup: PdfRedactor and PdfSigner paths in Phase 1 create no temp files (in-memory rasterization / direct write to the requested output); `finally` closes documents and recycles bitmaps. No broad cache deletion introduced.
Files intentionally untouched (out of Phase 1): `CacheManager` substring/heuristic cleanup, `FileManager.clearCache` non-recursive behavior.

## 8. CI/CD

File: `scripts/static_analysis.sh`
Before: error/warning counters incremented inside `grep | while read` subshells — `ERRORS`/`WARNINGS` always ended 0, script always exited 0.
After: all `while read` loops now use process substitution `done < <(grep ...)` so counters live in the main shell. Verified: planted `/sdcard/...` literal → `EXIT=1`; clean tree → `EXIT=0`.
Reason: ERROR checks (hardcoded paths, `System.getenv` version, Play refs in FOSS flavors) now actually fail CI.

File: `.github/workflows/test.yml`
Before: `continue-on-error: true` on the PlayStore release build step.
After: removed — ProGuard-breaking release builds now fail the job. YAML parse OK.

File: `.github/workflows/ensure-release-files.yml`
Before: `./gradlew ... || echo "build failed"` and `gh release upload ... || echo "..."` swallowed every failure; `git checkout ... || true`.
After: all build/upload commands set `FAILED=1` on failure; upload failures count; `git checkout` failure prints a warning; final block exits 1 when `FAILED=1`. YAML parse OK.

File: `.github/workflows/deploy.yml`
After: only the signing-material removal above. `continue-on-error: true` on the two store-upload steps retained (deliberate policy: release is created with a per-channel ❌ status and the "both failed" gate still fails the job). No `|| true` elsewhere. Authorization header for Indus uses `secrets.INDUS_APP_STORE_KEY` — that is a token, not signing material; kept.

Test: `StaticAnalysisScriptTest.kt` plants a hardcoded-path probe file, asserts exit 1, removes it, asserts exit 0.

## 9. Tests Added/Changed

New: `PdfRedactorTest` (8 tests), `PdfSignerTest` (2), `OcrConfidenceTest` (3), `FlavorGatingTest` (3), `StaticAnalysisScriptTest` (1). No `assert(true)` tests; all assert behavior. Nothing depending on unavailable external services. Existing tests: only `SettingsScreen.kt` import line removed; no existing test referenced `PdfTools`.
GUI-verified the shell script's exit behavior manually (§8) — reported below in §10.

## 10. Build Verification

- No Android SDK (`ANDROID_HOME` unset), no JDK 17 available (only OpenJDK 25), and per user instruction no APK build was executed. Therefore:
  - `./gradlew assemble{Playstore,Fdroid,Opensource}Debug|Release`: NOT RUN.
  - `./gradlew test{Fdroid,Playstore,Opensource}DebugUnitTest`: NOT RUN (new tests uncompiled).
  - `./gradlew lint{Flavor}Debug`: NOT RUN.
  - Merged-manifest verification for Playstore/Fdroid/Opensource: NOT RUN.
- Verification that WAS performed: YAML parse of all 6 workflows (OK), `bash -n`-equivalent behavior of `static_analysis.sh` with planted/clean inputs (EXIT 1/0), XML well-formedness of new `app/src/playstore/AndroidManifest.xml`, grep-based confirmation of every claim (symbols, callers, secret references, confidence constant removed), import/call-site trace for the deleted `PdfTools`.

## 11. Files Changed

- `.github/workflows/deploy.yml` — removed keystore/key-password/alias/store-password upload + keystore logging.
- `app/build.gradle.kts` — removed KEY_ALIAS value logging; added playstore network-permission comment.
- `app/src/playstore/AndroidManifest.xml` — NEW, declares INTERNET for the playstore variant only.
- `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfRedactor.kt` — secure rasterize-and-replace redaction; explicit failure of `redactText`; removed `flattenAfterRedaction` no-op parameter.
- `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfOcrProcessor.kt` — honest page confidence (mean of word confidences); `averageWordConfidence` extracted for testability.
- `app/src/fdroid/java/com/yourname/pdftoolkit/domain/operations/OcrEngine.kt` — normalize Tesseract confidence to 0–1.
- `app/src/opensource/java/com/yourname/pdftoolkit/domain/operations/OcrEngine.kt` — same.
- `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfSigner.kt` — KDoc corrected to visual signature.
- `app/src/main/res/values/strings.xml` — "Visual Signature" wording.
- `README.md`, `metadata/com.yourname.pdftoolkit.yml`, `fastlane/metadata/android/en-US/full_description.txt` — accurate sign feature wording.
- `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SettingsScreen.kt` — removed unused `PdfTools` import.
- `app/src/main/java/com/yourname/pdftoolkit/util/PdfTools.kt` — DELETED.
- `scripts/static_analysis.sh` — counter/subshell bug fixed (process substitution).
- `.github/workflows/test.yml` — removed `continue-on-error: true` from release build.
- `.github/workflows/ensure-release-files.yml` — failures propagate (`FAILED=1` + `exit 1`).
- `app/src/test/java/com/yourname/pdftoolkit/domain/operations/PdfRedactorTest.kt` — NEW.
- `app/src/test/java/com/yourname/pdftoolkit/domain/operations/PdfSignerTest.kt` — NEW.
- `app/src/test/java/com/yourname/pdftoolkit/domain/operations/OcrConfidenceTest.kt` — NEW.
- `app/src/test/java/com/yourname/pdftoolkit/FlavorGatingTest.kt` — NEW.
- `app/src/test/java/com/yourname/pdftoolkit/StaticAnalysisScriptTest.kt` — NEW.

## 12. Files Intentionally Not Changed

- `CacheManager.kt`, `FileManager.kt`, `OutputFolderManager.kt` (cache/storage refactor deferred).
- `deploy.yml` continue-on-error on store uploads (deliberate per-channel policy).
- `DocxViewerScreen`, `HtmlToPdfConverter` internals (beyond manifest/flag gating).
- No other flavor manifests created for fdroid/opensource (they remain offline by absence of `INTERNET`).
- No dependency version/Gradle/AGP changes.

## 13. Remaining Risks

- P0 previously identified (key sent to third party) is closed in the workflow, but the keystore must be considered compromised and rotated with the Play Console; not done here.
- Android 11+ behavior of `OutputFolderManager` direct File writes (still falls back to filesDir) — Phase 2.
- `PdfCompressor` concurrent temp-file prefix deletion — Phase 2.
- Many operations still lack `ensureActive` cancellation — Phase 2.
- PPTX/OfficeConverter memory behavior — Phase 2.
- `HAS_OCR` route gate vs OCR card in ToolsScreen — Phase 2.

## 14. Unverified Items

- All Gradle tasks (compile, unit tests, lint, resource shrinking, R8 for all three flavors): NOT RUN — no Android SDK/JDK 17 in this environment and local APK builds were forbidden.
- Merged per-flavor manifests (INTERNET present only in playstore): NOT verified by a real build.
- Behavior of the rewritten `PdfRedactor` under Robolectric: test written but not executed.
- Indus API acceptance of an AAB uploaded without its deprecated keystore fields: NOT verified (no CI access).
- `StaticAnalysisScriptTest` and `FlavorGatingTest` under Robolectric: written, not executed.

## 15. Phase 2 Recommendation

Proceed to Phase 2 only after a machine with SDK + JDK 17 runs the Phase-1 unit tests and at least `assembleFdroidDebug` to prove compilation of the new redactor/OCR confidence code and the new `PdfRedactorTest`. Phase 2 should address cancellation coverage, `MemoryUsageSetting` consistency, the dual recent-files backends, Room migrations, FileProvider path narrowing, and the UI touch-targets/localization items.
