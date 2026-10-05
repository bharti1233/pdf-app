# PHASE 2 — Architecture Cleanup & Technical Debt Report

## 1. Executive summary

Phase 2 removed ~590 lines of dead code, legacy config, and unused resources;
strengthened 3 weak test files (4 encrypted-viewer tests made real, 1 tautology
replaced, dead flow-collector removed); updated 1 stale doc claim; and left
all architecture, flavors, dependencies, package identity, and security
behavior intact. CI: Static Analysis, Build Verification, Crash Pattern Check,
and Debug APK build pass. Unit Tests show only the known rotating pre-existing
flakes (this run: `testCompressPdfToTargetSize_Failure`,
`testRotateWithNegativeOrModuloDegrees` — both failed identically BEFORE Phase
2) plus one Phase-2 test-setup issue that was diagnosed and fixed (see §8).

## 2. Files/components investigated

- `ui/navigation/Screen.kt` (dead route + dead mapper)
- `util/ExceptionHandler.kt`, `domain/PdfBoxInitializer.kt`,
  `util/ImageViewerUtils.kt`, `ui/components/SaveComponents.kt` (dead-file candidates)
- `data/HistoryManager.kt` vs `data/SafUriManager.kt` (alleged duplication)
- `util/ThemeManager.kt`, `LanguageDataStore.kt`, `LanguageManager.kt`,
  `util/ToolSettingsStore.kt` (alleged duplication)
- `fdroid/` vs `opensource/` vs `playstore/` source trees + tessdata assets
- Coil vs Glide, `material-icons-extended`, fragment-ktx, Room, DataStore
- `gradle.properties`, `settings.gradle.kts`, `app/build.gradle.kts`,
  `AndroidManifest.xml` (legacy flags)
- `PdfViewerScreen.kt` (~2491 LOC), `OfficeConverter.kt` (~1939 LOC) (split candidates)
- `EncryptedPdfViewerTest`, `CompressScreenTest::testFilePickerFilter_isPdf`,
  `RatingManagerTest::testIncrementUsage`, androidTest `NavigationTest`
- `res/drawable/app_icon.png`, `res/values/ic_launcher_background.xml`
- README offline/permission claims

## 3. Dead code removed

| What | Evidence of deadness | Verification |
|------|----------------------|--------------|
| `Screen.PdfViewerLegacy` | Only declaration in `Screen.kt:38`; no composable registered in `AppNavigation.kt`; zero navigations (grep). Source even commented "unused". | CI Build Verification compiles nav graph |
| `Screen.fromFeatureTitle` | Only declaration in `Screen.kt`; zero callers repo-wide (grep incl. tests) | Same |
| `util/ExceptionHandler.kt` (155 LOC) | Zero imports of `com.hmx.toolkit.util.ExceptionHandler`; `PdfViewerViewModel` matches are `kotlinx.coroutines.CoroutineExceptionHandler` (substring) | Same |
| `domain/PdfBoxInitializer.kt` (47 LOC) | Zero imports; init is done directly via `PDFBoxResourceLoader.init` in `PdfToolkitApplication`, `OfficeConverter` (3×), `PdfViewerViewModel` | Same |
| `util/ImageViewerUtils.kt` (277 LOC) | Zero references outside its own file; View/`ImageView`-based Glide helpers in a 100% Compose app; no `AndroidView` consumers | Same |
| `res/drawable/app_icon.png` | Zero `R.drawable`/XML references; launcher uses mipmap PNGs | Debug APK builds; `aapt` unaffected |
| `res/values/ic_launcher_background.xml` | Zero references; no `mipmap-anydpi-v26` adaptive-icon XML exists | Same |

## 4. Duplicate code consolidated

None merged. Investigated and deliberately kept (see §11): HistoryManager vs
SafUriManager serve different contracts (operation log vs URI-permission
persistence); Theme/Language/ToolSettings stores have distinct callers and
schemas; fdroid vs opensource `ReviewManager`/`ReviewHelper` differ
behaviorally (Play-Store-web vs GitHub-releases fallback); OcrEngine/scanner
fdroid-vs-opensource differ only in comments but consolidation needs a shared
sourceSet + FOSS re-verification — deferred, not forced.

## 5. Dependencies removed

None. Proven still required: Coil (4 UI files), Glide (`CacheManager`
disk-cache clearing + `LicensesDialog`), `material-icons-extended`
(dozens of `Icons.Default.*` call sites, e.g. `Compress`,
`DocumentScanner`, `AutoFixHigh`), fragment-ktx (uCrop/legacy viewer),
Room/DataStore (recents + prefs). Removal of any would break compilation.

## 6. Build/config cleanup

- `gradle.properties`: deleted unread legacy `VERSION_CODE`/`VERSION_NAME`
  (build script line 20–21 and all workflows read only `APP_*`; verified).
- `AndroidManifest.xml`: deleted stale
  `tools:overrideLibrary="androidx.pdf,..."` (no such dependency declared or
  referenced anywhere; merger no longer needs the override).
- `app/build.gradle.kts`: deleted `multiDexEnabled = true` (minSdk 26 ⇒
  native multidex; flag is a no-op).
- `settings.gradle.kts`: deleted unused sonatype-snapshots repo (no
  `-SNAPSHOT` dependency declared; google/mavenCentral/jitpack retained).
- Kept deliberately: `enableJetifier` (needs transitive artifact scan to
  remove safely), `requestLegacyExternalStorage` (still effective on API 29),
  `BaseVariantOutputImpl` rename hook (works; no public-API equivalent),
  deprecated `getExternalStoragePublicDirectory` call sites (behavior change).

## 7. Architecture refactors

None performed. `PdfViewerScreen` and `OfficeConverter` were inspected; no
split has a behavior-preserving boundary provable without local execution
and neither has covering tests, so cosmetic decomposition was refused.
`SaveComponents.kt` (audited earlier as "unused") was re-checked and found
LIVE via `ui.components.*` wildcard imports (`SaveLocationSelector` used by
12 screens) — not touched; audit corrected.

## 8. Test improvements

- `EncryptedPdfViewerTest`: 4 tests silently passed via missing
  `test_pdfs/*.pdf` fixtures. Now generates both encrypted fixtures at test
  time with PDFBox (`StandardProtectionPolicy`, passwords `secret123` /
  `pässwörd123`) and asserts real viewer states. First CI run exposed a
  setup bug in MY new code (`FileNotFoundException` writing into a fresh
  subdir) — fixed by writing fixtures to cacheDir root like every other PDF
  test, plus an existence check. No assertions weakened.
- `CompressScreenTest::testFilePickerFilter_isPdf`: literal-vs-literal
  tautology replaced with a behavioral `FileManager.isValidPdf` false-path
  assertion (unresolvable URI must not validate).
- `RatingManagerTest::testIncrementUsage`: removed dead SharedFlow collector
  block (collected into a var, never asserted, admitted flaky in comments);
  kept the real threshold asserts (false×3, true on 4th); dropped now-unused
  flow imports. Verified `emit` can't hang the test (no-subscriber emit
  returns immediately; suite was green with this shape before).
- androidTest `NavigationTest` (try/catch swallow): left untouched — no
  emulator in CI, so no verification path; documented, not hidden.

## 9. Resource cleanup

Deleted the two unreferenced files in §3. No strings/layouts/themes removed:
15-locale `strings.xml` and theme files are all reachable via generated `R`.

## 10. Documentation updates

- `README.md`: "No internet permission" → qualified per-flavor (Play flavor
  declares INTERNET for URL→PDF since Phase 1). Signature/redaction/OCR
  wording already truthful from Phase 1 — verified, no change needed.
- `AGENTS.md`: F-Droid metadata path updated by the package migration
  (`com.hmx.toolkit.yml`).

## 11. Files intentionally NOT removed and why

- `SaveComponents.kt` — live (12 screens via wildcard import).
- `HistoryManager.kt` + `SafUriManager.kt` — different contracts, both widely
  called; merge = redesign + data migration, out of scope.
- fdroid/opensource trees — behavioral differences in review fallback;
  consolidation needs build + FOSS verification.
- Coil/Glide/icons-extended/fragment-ktx/Room/DataStore — all referenced.
- `PdfViewerScreen`/`OfficeConverter` splits — refused as cosmetic without
  guarding tests.
- `enableJetifier`, `requestLegacyExternalStorage`,
  `getExternalStoragePublicDirectory`, `BaseVariantOutputImpl` — behavior or
  unverifiable-without-build risk; documented.
- `PdfCompressorTest::testCompressPdf_basic` — known flake (Phase 1B);
  untouched.

## 12. Behavior/regression verification

No intentional behavior change in this phase (deletions are dead code,
config no-ops, or test-only). Compilation-affecting changes are covered by
CI Build Verification + Debug APK build (both green). Runtime behavior was
not executed locally (resource-limited device) and CI has no emulator —
stated, not claimed.

## 13. Build/test results

CI run 37271713257 (commit `a6a91b1`): Static Analysis PASS, Build
Verification PASS (both debug flavors compile with manifest/config changes),
Crash Pattern Check PASS, Debug APK Build PASS (signed, verified, uploaded),
Unit Tests FAIL with 3 failures — 1 mine (fixed, §8) + 2 pre-existing flakes
(`testCompressPdfToTargetSize_Failure`,
`testRotateWithNegativeOrModuloDegrees`, both failed identically pre-Phase-2
in run 37197292952; Phase-2 diff touches neither file nor their code paths).
Fix commit pending CI re-run at report time.

## 14. Known limitations

- Unit Tests red on rotating pre-existing flakes; no emulator, so androidTest
  never runs in CI.
- Encrypted-viewer fix not yet re-verified in CI at report time.
- Production signing secrets absent → release-APK job correctly fails fast
  (by design); debug APK is the distributable.

## 15. Remaining technical debt

- fdroid/opensource near-duplicate trees (needs shared sourceSet + FOSS check).
- `HistoryManager` (unvalidated history URIs) vs `SafUriManager` (validated
  recents) dual systems.
- `PdfViewerScreen`/`OfficeConverter` size (needs tests first, then split).
- `enableJetifier`, API-29 storage flag, deprecated pictures/documents
  directory access (behavior changes, need device testing).
- Rotating unit-test flakes (Phase 1B) incl. `testCompressPdf_basic`.

## 16. Git diff/stat summary

Commit `a6a91b1`: 14 files, +40/−590 (3 source deletions + 2 test-file…
actually 3 util/domain deletions, 2 resource deletions, Screen.kt −45,
config one-liners, 3 test files strengthened). Follow-up fix commit for the
encrypted-test setup (this report's §8/§13) is separate and minimal.
