# PDF Toolkit — Complete Repository Audit

**Audit date:** 2026-10-04
**Scope:** Full source tree, build config, CI, docs, metadata. Audit only — no files were modified (this report is the only artifact added).
**Build status:** NOT VERIFIED. No Android SDK / `ANDROID_HOME` is present in this environment (`java` 25 only), so no `./gradlew` build was executed. Statements about "should build" are derived from static analysis only.

---

## 1. Executive Summary

PDF Toolkit is a single-module Android app (`:app`, namespace `com.yourname.pdftoolkit`) implementing ~25 PDF/image tools on PDFBox-Android, Android's `PdfRenderer`/`PdfDocument`, Apache POI, CameraX, and a WebView DOCX viewer. Three product flavors (`playstore`, `fdroid`, `opensource`) exist with real isolation: ML Kit OCR/document scanner only in `playstore`, Tesseract only in `fdroid`/`opensource`, Play Review API Play-only.

Architecture is Compose + single-NavHost + mixed MVVM: ~12 screens use ViewModels; most tool screens call `domain.operations.*` directly from composable coroutines. `data/` is concrete managers, not a repository layer; no DI; domain classes take `Context` freely.

Most significant risks are semantic, not structural:
- `PdfRedactor` is visual-only (overlay boxes) and its `flattenAfterRedaction` flag is a silent no-op; `redactText` always throws `UnsupportedOperationException`. No UI calls it.
- "Sign PDF" embeds a *visual* image, not a cryptographic signature — README/metadata overclaim.
- `util/PdfTools.flattenAndSavePdf` byte-copies instead of flattening and is dead code.
- `PdfOcrProcessor` reports a hardcoded 0.85 confidence.
- `MainActivity` ignores `ACTION_SEND_MULTIPLE` despite the manifest declaring it.
- URL→PDF in the Play flavor loads `WebView.loadUrl(url)` but the manifest declares no `INTERNET` permission.
- `PdfMetadataManager.removeMetadata` leaves XMP packets intact.
- `PdfScanner`'s KDoc advertises "auto-crop simulation" that does not exist.

Testing: many green-but-empty tests (`assert(true)`, unasserted flow collects, missing fixtures, swallowed navigation failures). CI: `static_analysis.sh` always exits 0 (subshell counter bug), release checks use `continue-on-error: true`, `ensure-release-files.yml` swallows build failures, `deploy.yml` POSTs the signing keystore + passwords to a third-party SaaS (Indus).

Privacy: `allowBackup=true` with only cache excluded; review counters and operation history persist in plaintext SharedPreferences; `ReviewLogger` logs usage at DEBUG. No INTERNET permission (matches "offline" claim, with the noted exceptions: ML Kit runtime downloads in Play flavor; URL→PDF by definition fetches remote URLs).

---

## 2. Repository Inventory

```
Pdf_Tools-master/
├── AGENTS.md, README.md, CHANGELOG.md, LICENSE (Apache-2.0)
├── index.html, licenses.html, privacy-policy.html
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradlew.bat
├── gradle/wrapper/gradle-wrapper.properties   (Gradle 8.11.1)
├── metadata/com.yourname.pdftoolkit.yml
├── fastlane/metadata/android/en-US/
├── docs/  (VERSIONING.md, VERSION_HISTORY.md, implementation-plan-*.md, reports/, saf-crash-hardening-*.md, localization/)
├── ci/fdroid/validate_metadata.sh
├── release/indusdocs.txt
├── scripts/  static_analysis.sh, lint_check.sh, verify_playstore_build.sh, jules_setup.sh
├── store_assets/, distribution/whatsnew/
├── .github/workflows/  build-release.yml, deploy.yml, ensure-release-files.yml,
│                       manage-releases.yml, pin-release.yml, test.yml
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/docx_viewer/  (viewer.html, docx-preview.min.js, jszip.min.js, fonts/*.ttf)
        │   ├── res/  (values + 14 values-* locales, drawable, mipmaps, xml)
        │   └── java/
        │       ├── android/print/PrintAdapterHelper.kt
        │       └── com/yourname/pdftoolkit/
        │           ├── PdfToolkitApplication.kt
        │           ├── data/  FileManager.kt, HistoryManager.kt, SafUriManager.kt, local/{AppDatabase,RecentFileEntity,RecentFilesDao}.kt
        │           ├── domain/  PdfBoxInitializer.kt, imposition/{ImpositionEngine,ImpositionModel,ImpositionPdfExporter}.kt,
        │           │            operations/  24 files (see §8)
        │           ├── review/  ReviewIntegration, ReviewLifecycleTracker, ReviewLogger, ReviewPreferences, UsageTracker
        │           ├── ui/  MainActivity.kt, components/ (CommonComponents, HistorySidebar, LicensesDialog, PdfThumbnailGrid, SaveComponents),
        │           │        crop/CustomUCropActivity.kt, navigation/{AppNavigation,Screen}.kt,
        │           │        screens/  27 files + imposition/{InteractivePreviewEngine,PrintImpositionStudioScreen,PrintImpositionViewModel}.kt,
        │           │        theme/{Color,Theme,Type}.kt
        │           └── util/  16 files (CacheManager, CropHelper, ExceptionHandler, FileOpener, ImageProcessor, ImageViewerUtils,
        │                       LanguageDataStore, LanguageManager, MemoryGuard, OutputFolderManager, PdfTools, PrintUtils,
        │                       RatingManager, SafeLauncher, ThemeManager, ToolSettingsStore)
        ├── playstore/   OcrEngine.kt, SmartDocScanner.kt, ReviewManager.kt, ReviewHelper.kt
        ├── fdroid/      same 4 files + assets/tessdata/eng.traineddata
        ├── opensource/  same 4 files + assets/tessdata/eng.traineddata
        ├── test/        23 test files + resources/robolectric.properties
        └── androidTest/ 7 test files
```

**Counts:** ~100 Kotlin files in `main` (~48k LOC), 24 operation classes, 30 screen files, 23 unit + 7 androidTest, 6 workflows, 4 scripts. Flavor-specific code = 4 classes × 3 flavors with small diffs.

**Classification:**
- Core: MainActivity, AppNavigation, Screen, PdfToolkitApplication, util managers, domain/operations.
- Feature-specific: each screen + its domain class pair.
- Flavor-specific: the 4 duplicated classes; Tesseract assets.
- Build-only: gradle, workflows, scripts, ci, fastlane, metadata.
- Test-only: test/, androidTest/.
- Legacy/vestigial: `Screen.PdfViewerLegacy`, `Screen.fromFeatureTitle`, `PdfTools` (imported but never used), commented-out MuPDF deps, stale `androidx.pdf` manifest override, `PrintAdapterHelper` retained for the DOCX converter.
- Apparently unused (traced): `util/PdfTools.kt` (SettingsScreen.kt:35 imports it, `PdfTools.` never invoked), `navigateToPdfTool` (drops all tools except compress/watermark), `Screen.PdfViewerLegacy`/`fromFeatureTitle`.

No `androidx.pdf` dependency is declared, but `AndroidManifest.xml` keeps `<uses-sdk tools:overrideLibrary="androidx.pdf,..."/>` — stale leftover.

---

## 3. Architecture

Actual: single-module, Compose UI + single NavHost, mixed MVVM, no repository interfaces, no DI.

- ViewModel screens: PdfViewer, DocxViewer, DocToPdf, Merge, Watermark, Flatten, SignPdf, FillForms, ScanToPdf, Ocr, Annotation, PrintImpositionStudio.
- Direct-domain screens: Split, Compress, Convert, PdfToImage, Extract, Rotate, Security, Metadata, PageNumber, Organize, Reorder, Unlock, Repair, HtmlToPdf, ExtractText, ImageTools — transient state in `remember`, lost on config change.
- `PdfToolkitApplication.onCreate` uses `runBlocking` for language/theme init (main-thread block at startup).
- Dual recent-file systems: `SafUriManager` (Room, permission-validated) vs `HistoryManager` (SharedPreferences JSON, unvalidated).

Large/complex classes (future decomposition — none performed):

| File | LOC | Why large | Mixed | Risk |
|---|---|---|---|---|
| `ui/screens/PdfViewerScreen.kt` | 2491 | one mega-composable + 6 parts | gestures, rendering, annotations, search, dialogs, share | hard to test; touch regressions |
| `domain/operations/OfficeConverter.kt` | 1939 | 3 converters one file | POI parsing, PDF layout, bitmaps | any change touches all |
| `ui/screens/DocxViewerScreen.kt` | 1259 | WebView + native viewer + search | JS bridge, export | coupled to assets |
| `ui/screens/PdfViewerViewModel.kt` | 1200 | viewer state machine | render cache, search, annotations, undo | one VM, many concerns |
| `ui/screens/ScanToPdfScreen.kt` | 1126 | CameraX + scanner + crop | camera lifecycle in screen | camera bugs |
| `domain/operations/PdfCompressor.kt` | 1083 | 3 strategies + binary search | temp files, rendering, metadata | prefix-delete race |
| `ui/screens/OcrScreen.kt` | 1040 | inline VM + settings + result | OCR config + process + display | confidence hardcoded |
| `ui/navigation/AppNavigation.kt` | 910 | 30 destinations | routing + scaffold + intent entry | grows monotonically |
| `domain/imposition/ImpositionEngine.kt` | 911 | layout math | cohesive, just big | low |

---

## 4. Build System

- One module `:app`. AGP 8.9.1, Kotlin 1.9.10, KSP 1.9.10-1.0.13, Gradle wrapper 8.11.1, JDK 17 target, Compose compiler 1.5.3, compileSdk/targetSdk 36, minSdk 26, NDK 28.0.12433510.
- `gradle.properties` holds both `VERSION_CODE/NAME` and `APP_VERSION_CODE/NAME`; build script + F-Droid `UpdateCheckData` read only `APP_*`. Legacy pair is dead config.
- Three flavors on dimension `store`; fdroid/opensource Tesseract dirs differ only in comments. MuPDF deps commented out.
- Signing: local `keystore.properties` or CI env vars; CI log prints presence of each secret and the KEY_ALIAS value (low-severity log leak).
- R8 full mode + resource shrinking; packaging excludes strip duplicate META-INF and BouncyCastle PQC tables (~8 MB claim).
- Deprecated/legacy: `android.enableJetifier=true` (AGP 9 will drop it), `requestLegacyExternalStorage=true` (no-op on API 30+), `multiDexEnabled=true` on minSdk 26 (unnecessary), `tools:overrideLibrary` for undeclared `androidx.pdf`, `BaseVariantOutputImpl` internal API use, `composeOptions.kotlinCompilerExtensionVersion` (deprecated location), `kotlinOptions` block placement.
- Repositories: google/mavenCentral/jitpack/gradlePluginPortal + unnecessary sonatype snapshots. `FAIL_ON_PROJECT_REPOS` good.
- Theoretical build: coherent. UNVERIFIED — no SDK/JDK 17 in this environment. CI start-up would also need `CI=true` + keystore env to fully exercise the release signing path.

---

## 5. Dependencies

| Dependency | Purpose | Version | Flavors | License | Notes |
|---|---|---|---|---|---|
| androidx.core-ktx | base | 1.12.0 | all | Ap-2.0 | |
| lifecycle-runtime-ktx, viewmodel-compose | lifecycle | 2.7.0 | all | Ap-2.0 | |
| documentfile | SAF | 1.0.1 | all | Ap-2.0 | |
| appcompat | theme/locale/uCrop | 1.6.1 | all | Ap-2.0 | |
| recyclerview | viewer | 1.3.2 | all | Ap-2.0 | |
| exifinterface | EXIF | 1.3.7 | all | Ap-2.0 | |
| compose-bom | UI | 2023.10.01 | all | Ap-2.0 | icons-extended bloats APK |
| navigation-compose | nav | 2.7.5 | all | Ap-2.0 | |
| pdfbox-android | PDF engine | 2.0.27.0 | all | Ap-2.0 | upstream PDFBox 2.0.x had CVEs fixed later — run a real CVE scan (UNVERIFIED) |
| poi, poi-ooxml, poi-scratchpad | Office | 5.2.5 | all | Ap-2.0 | heavy |
| kotlinx-coroutines-android | async | 1.7.3 | all | Ap-2.0 | |
| camera-* | scan | 1.4.1 | all | Ap-2.0 | 16KB aligned |
| mlkit text-recognition | OCR | 16.0.1 | playstore | proprietary | |
| play-services-mlkit-document-scanner | doc scan | 16.0.0 | playstore | proprietary | transitively adds INTERNET |
| play:review | review | 2.0.1 | playstore | proprietary | |
| tesseract4android | OCR | 4.9.0 | fdroid,opensource | Ap-2.0 | duplicated assets |
| coil-compose | images | 2.5.0 | all | Ap-2.0 | |
| glide + ksp | images | 4.16.0 | all | BSD-like | overlaps Coil |
| ucrop | crop | 2.2.9 | all | Ap-2.0 | |
| room-* | recents | 2.6.1 | all | Ap-2.0 | v1, no migrations |
| datastore-preferences | prefs | 1.0.0 | all | Ap-2.0 | 4 DataStore files |
| fragment-ktx | uCrop compat | 1.8.5 | all | Ap-2.0 | |
| junit, robolectric, espresso, compose ui-test | tests | 4.13.2/4.11.1/3.5.1 | — | Apache/EPL | |

Findings: Coil+Glide overlap (LOW), sonatype snapshots unused (LOW), material-icons-extended size (LOW), no version catalog (INFO), BouncyCastle MIT via pdfbox (OK after PQC exclusion). No dependency is duplicated in `fdroid` and `opensource` blocks beyond Tesseract (intentional duplication of the same line in two blocks — acceptable but churn-prone).

---

## 6. Navigation

Routes (Screen.kt) map 1:1 to composables in AppNavigation.kt with two exceptions: `Screen.PdfViewerLegacy` (dead, no composable registered) and `Ocr` (registered only when `BuildConfig.HAS_OCR` — currently always true, and the tool card is not flavor-gated, so a future flavor with `HAS_OCR=false` would have a tappable card that silently fails navigation). Route string drift: `Screen.Compress.route`/`Watermark.route` vs NavHost's `?uri=&name=` variants. `navigateToPdfTool` whitelist only routes `compress`/`watermark` — all other tool names dropped. `SEND_MULTIPLE` handled nowhere. Entry points: LAUNCHER, VIEW/SEND (PDF/DOCX/image) → cache copy → `pdf_viewer_direct` or DocxViewer. No deep links. `Screen.Home` route aliases `tools`. Back via `enableOnBackInvokedCallback` + configChanges on MainActivity. State restoration: ViewModels survive; transient dialog/progress state in direct-domain screens does not.

---

## 7. UI/Compose

- Shell: NavHost + bottom bar + top bar. Theme/language flow DataStore → AppCompatDelegate → `PDFToolkitTheme`.
- 0 `@Preview` annotations. Several hardcoded unlocalized contentDescriptions and strings (ExtractScreen.kt:272, PrintImpositionStudioScreen.kt:94/214/236, InteractivePreviewEngine.kt:160-206, DocxViewerScreen.kt:1017/1058, SignPdfScreen.kt:583/830, PdfThumbnailGrid.kt:225, SettingsScreen.kt:472-536, FilesScreen.kt:200/233/336).
- Coroutine hygiene: no GlobalScope; `runBlocking` on main in `MainActivity.handleIntent` (SAF cache copy) and `LanguageManager.initializeLanguage`.
- Screens with local processing state (SplitScreen.kt:80-98 etc.) lose progress/dialogs on rotation.
- PdfViewerScreen: pinch/pan via `awaitEachGesture`, LazyColumn `dispatchRawDelta` compensation, `produceState` page rendering, LruCache bitmaps, non-intercepting overlay canvases except while annotating — consistent with AGENTS.md.
- DocxViewerScreen drives `viewer.html` via `evaluateJavascript` with 128 KB chunking and search (nextMatch/prevMatch); legacy `.doc` renders native AnnotatedString.
- Accessibility basics present (decorative `contentDescription=null` on tool icons; small touch targets in DocViewerActionButton). No RTL-specific check.

---

## 8. PDF Engine

See the table in the audit working notes; key per-operation facts:

| Op | Library | Cancel | Progress | Temp/cleanup | Memory | Risk |
|---|---|---|---|---|---|---|
| Merge | PDFBox | yes | yes | setupTempFileOnly | streamed | LOW |
| Split | PDFBox | none | yes | per-page ByteArrayOutputStream | whole doc heap | LOW |
| Compress | PDFBox+PdfRenderer | yes | yes | cacheDir prefix-delete race | ≤2048px rerender | fallback-file deletion race |
| Rotate | PDFBox | yes | yes | none | `getPageRotation` bare heap load | LOW |
| Organize/Reorder | PDFBox+PdfRenderer | none | yes | temp thumbs | 2nd full doc on reorder | MED |
| Watermark | PDFBox | none | 0-100 | none | bare heap load | LOW |
| PageNumberer | PDFBox | yes | yes | none | bare heap load | LOW |
| Metadata | PDFBox | no | yes | none | `stream.available()` size bug; XMP kept on remove | MED |
| Flattener | PDFBox | none | 0-100 | none | bare heap load | formsFlattened may report 0 |
| FormFiller | PDFBox | none | 0-100 | none | bare heap load | LOW |
| Annotator | PDFBox | none | 0-100 | per-note bitmaps | bare heap load | LOW |
| OCR PDF | PDFBox+OcrEngine | yes | 0-100 | temp OCR cache | 4MP cap | confidence hardcoded |
| Repairer | PDFBox | none | yes | temp file | heap fallback load | LOW |
| Security/Unlock | PDFBox | none | yes | none | substring sniffing; owner==user pw | MED |
| Signer | PDFBox | none | 0-100 | signatures/ orphans | bare heap load | visual-only |
| Redactor | PDFBox | none | yes | none | bare heap load | stub redactText; no-op flatten flag |
| TextExtractor | PDFBox | none | coarse | none | whole-text strings | hasExtractableText reads p1 |
| Scanner | PDFBox | none | 0-100 | cacheDir/scans | 3000px cap; 36MB IntArray | KDoc auto-crop false claim |
| ImageConverter | PDFBox | yield-only | yes | none | RGB_565 | OOM catches |
| OfficeConverter | POI+PDFBox | none | none | none | all slide bitmaps held | exceptions/swallowed |
| HtmlToPdf | WebView+PdfDocument | coroutine | yes | WebView cache | WebView leak on error | fixed delays |
| WebViewDocxToPdf | WebView+print | 100s timeout | none | none | full base64 | attached-WebView client kept |
| PdfTools.flatten | none (copy) | n/a | n/a | leak on error | — | not a flatten |

Cross-cutting: `MemoryUsageSetting` discipline uneven; progress callbacks inconsistent (0..1 vs 0-100); error-message sniffing for passwords; OfficeConverter and DocumentSearchEngine use `printStackTrace` + empty results.

---

## 9. Storage & SAF

- Intent entry: synchronous cache copy to `cacheDir/shared_files` wrapped in FileProvider (`file_paths.xml` grants `cache-path path="."`, `files-path path="."`, `external-path path=".", plus external-files-path for the output folder) — broader than the doc comment implies.
- Recents healed in `SafUriManager.loadRecentFiles`; history (`HistoryManager`) never re-validated.
- Cache: `FileManager.clearCache` non-recursive; `CacheManager.clearPdfOperationsCache` deletes all `*.pdf`/`temp_*` in cache root (no age check); `deleteTemporaryFile` substring-matches "cache"; 100 MB auto-clean threshold uses only 24 h age.
- Output: `OutputFolderManager` uses deprecated `getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)/PDF Toolkit` + MediaScannerConnection, with MediaStore `IS_PENDING` path on Q+; on Android 11+ direct File writes silently fall back to `filesDir` — invisible split.
- Lifecycle test (conceptual): open → cache copy → process → MediaStore save → recents/history updated → permission revoking handled by pruning (recents) but not by history; `file://` URIs bypass permission checks in `SafUriManager`.
- Privacy: history + review counters plaintext; no opt-out.

---

## 10. OCR

Covered in §2 inventory. ML Kit (play) / Tesseract 4.9 (fdroid,opensource). `eng.traineddata` bundled per FOSS flavor (duplicated ~15-20 MB). English only in-repo. `OcrDpiUtil` caps pages at 4 MP. `PdfOcrProcessor` chunks 3 pages with yield, temp-file docs, cleanup in finally, per-image errors recorded. Confidence hardcoded 0.85. Flavor-specific `OcrEngine` is referenced by the common processor — abstraction is correct.

---

## 11. DOC/DOCX/HTML

DOCX view: `assets/docx_viewer/viewer.html` + docx-preview.min.js + jszip; WebView with javaScriptEnabled, domStorage, allowFileAccess; loads only local asset; JS bridge resumes coroutine; 45 s kill-switch + 100 s outer timeout; chunked base64 injection (128 KB) for >500 KB docs; legacy `.doc` uses the native POI renderer fallback in DocxViewerScreen. DOCX→PDF: WebView `createPrintDocumentAdapter` via `android.print.PrintAdapterHelper` (vendored helper still called). URL→PDF: `loadUrl(url)` in Play flavor only, but no INTERNET permission (§13). HTML→PDF: fixed A4 pagination, 1.5 s JS delay; WebView not destroyed on error (leak). No LICENSE field issue: Carlito/Tinos fonts are Google's Cros-W fonts (metric-compatible with Caladea/Cambria and Carlito/Calibri), Apache/OFL-compatible — UNVERIFIED per-font license files (not present in the asset dir).

---

## 12. Image Processing

`ImageProcessor` (609 LOC): downsample, convert, compress, EXIF-strip; `PdfScanner.loadAndProcessImage` color mode + contrast only (no auto-crop despite KDoc); `PdfScanner` B/W uses threshold histogram with per-page IntArray; `ImageConverter` renders pages to RGB_565 and recycles; `pdfToImages` one page at a time; decode caps: 3000 (scanner), 2048 (compress rerender), 4 MP (OCR), 3072 thumbnails. `System.gc()` calls between pages (cargo-culted). EXIF orientation honored via ExifInterface/Coil/Glide; metadata stripped on save (privacy good). Large images: bounded via caps but not documented in one place.

---

## 13. Security

### [CRITICAL] Signing key + passwords POSTed to third party
File: `.github/workflows/deploy.yml` lines 246-253
Evidence: `curl -F "file=@keystore.jks" -F "key_password=$KEY_PASSWORD" ... developer-api.indusappstore.com`.
Problem: release key shared with an external SaaS every deploy.
Impact: forged-app / supply-chain compromise.
Confidence: HIGH. Future action: upload the already-signed APK; rotate the key.

### [HIGH] Redaction is visual only; secure-flatten flag is a no-op
File: `PdfRedactor.kt:66,137-141` (no-op block), `:212-232` (stub), KDoc `:41-44`.
Problem: content remains extractable; no UI invokes it, and `flattenAfterRedaction=true` silently falls through.
Impact: privacy breach if user trusts "redaction".
Confidence: HIGH. Future action: implement content-stream rewrite or rasterize-then-replace; fail loudly; wire or delete the flag.

### [HIGH] "Digital signature" is a visual stamp
File: `PdfSigner.kt:89-92` vs README.md:86 / metadata Description.
Problem: overclaimed crypto claim.
Impact: legal/compliance risk.
Confidence: HIGH. Future action: reword docs; real CMS signature only if implemented.

### [HIGH] URL→PDF (Play) has no INTERNET permission
File: `AndroidManifest.xml` (no INTERNET), `HtmlToPdfConverter.kt:52,96`, `build.gradle.kts` playstore deps.
Problem: feature broken unless a transitive merged permission supplies INTERNET.
Impact: broken feature or accidental permission grant via transitive deps (undocumented).
Confidence: HIGH that manifest lacks the entry, MEDIUM that it fails (transitive may cover it). UNVERIFIED without a build.

### [MEDIUM] `PdfScanner` KDoc claims "auto-crop simulation"; code does color+contrast only
File: `PdfScanner.kt:93-97` vs `:236-248`. Impact: user expectation mismatch. Confidence HIGH.

### [MEDIUM] `PdfMetadataManager.removeMetadata` leaves XMP
File: `PdfMetadataManager.kt:162,186`. Impact: privacy (camera/GPS in XMP may persist). Confidence HIGH.

### [MEDIUM] FileProvider paths too broad
File: `file_paths.xml` (cache-path `.`, files-path `.`, external-path `.`). Impact: any file under app dirs shareable by grants. Confidence HIGH. Future: narrow to `shared_files/`.

### [MEDIUM] Backup includes all app files except cache
File: `backup_rules.xml`, `data_extraction_rules.xml`, manifest `allowBackup=true`. Impact: history/preferences/DB exported. Confidence HIGH.

### [MEDIUM] Review logger writes usage metadata to logcat
File: `ReviewLogger.kt:16` default DEBUG, `Log.d` usage. Confidence HIGH.

### [LOW] CI log prints which signing secrets are present and the key alias value
File: `app/build.gradle.kts` signingConfig println lines. Confidence HIGH.

### [LOW] `enableOnBackInvokedCallback=true` + `configChanges` on MainActivity may freeze config-driven state; low risk. Info.

### [LOW] WebView: `allowFileAccess=true` + `domStorageEnabled=true` on a view that only loads `file:///android_asset/...` — file access should be tightened to the asset origin. Confidence MEDIUM (attacker must supply a malicious DOCX; the WebView only loads a fixed asset so file access to that origin is the local filesystem — evaluateJavascript injection is not reachable without compromising the DOCX, which is parsed in JS and could abuse same-origin file access). Treat as MEDIUM.

---

## 14. Privacy

- No analytics SDK, no Firebase, no ads in opensource/fdroid; Play flavor uses ML Kit/Play Review (Google data processing under Play services).
- `privacy-policy.html` TL;DR "completely offline, files never leave your device" is accurate for the app itself; exceptions: ML Kit runtime model downloads (Play), URL→PDF (Play, remote by definition), and the Indus SaaS upload is a CI action not app behavior.
- Local data persisted: history (URIs/names/errors), review counters, DataStore prefs, Room recents — all unencrypted, included in backups, clearable via Settings (clear cache) but history requires the Settings entry point (verified present in SettingsScreen).
- Fonts Carlito/Tinos ship inside the APK; no license files for them in-repo. LOW.

---

## 15. Database & History

Room v1, no schema export, no migrations, no `fallbackToDestructiveMigration` — any schema change is a breaking change. `HistoryManager` SharedPreferences JSON rewrite per mutation, parse-failure → empty list, no validation of stored URIs. Four DataStore files (`app_preferences`, `theme_preferences`, `tool_settings`, + RatingManager SharedPreferences) — small duplication. Concurrency: Room DAO fine; HistoryManager not guarded.

---

## 16. Error Handling

Swallowed: `PdfScanner` page-drop on bitmap failure (`?: continue`), `PdfMerger.getTotalPageCount` per-file errors→0, `PdfFlattener` form-flatten exceptions, `DocumentSearchEngine`/`OfficeConverter.renderPptxToBitmaps` `printStackTrace`+empty, all `TextExtractor` string variants→`""`. Classification by substring `contains("password")` in Security/Unlock. Cancellation missing in 15+ operations. `runBlocking` on main in handleIntent (ANR risk on slow providers) and LanguageManager init. Partial outputs possible on compress fallback deletion. Empty catches in PdfCompressor.pruneMetadata and WebView lifecycle.

---

## 17. Performance & Memory

Bare `PDDocument.load` heap loads in Signer, Redactor, Flattener, FormFiller, Annotator, Watermarker, PageNumberer, Security, Repairer(diag), Rotator.getPageRotation, ImageConverter.imagesToPdf, ImpositionPdfExporter. `ImpositionPdfExporter` renders lossless ~3× DPI sheets with no cap. `OfficeConverter.renderPptxToBitmaps` holds all slide bitmaps. `PdfScanner` per-page IntArray B/W. `TextExtractor` whole strings. `WebViewDocxToPdfConverter` full base64 in memory. `PdfCompressor` 6-pass binary search with prefix-delete race. `System.gc()` in ImageConverter. `MemoryGuard` called inconsistently. `largeHeap=true` set globally. No ANR guards (yet).

---

## 18. Testing

Implemented but empty/fake: PdfViewerTest (assert(true)), PdfViewerScreenTest (assert(true)), PdfViewerCapabilityTest (empty body), EncryptedPdfViewerTest (all 4 tests early-return: `test_pdfs/*.pdf` missing), RatingManagerTest (collects SharedFlow value, never asserts), CompressScreenTest (tautological literal assert), NavigationTest (try/catch prints, cannot fail).

Real: PdfFlattenerTest, PdfCompressorTest (page counts, dedupe, cancellation), PdfMerger/Rotator/FormFiller/Scanner tests (Robolectric + reflection), ImpositionEngineCutStackTest (pure), ReviewSystemTest (sleep-based, flaky), SafUriManager/FileManager tests (conversion/formatting only), PdfViewerViewModelTest (real state asserts), FillFormsScreenTest (initial state), PrintUtilsTest (invalid URI→false), androidTest OcrEngineTest (real, never run in CI).

Missing coverage: redaction, crypto signature, storage-permission revocation, WebView security/bridge, DOCX conversion, OCR flavor parity, URI revocation, malformed/encrypted/large PDFs, cancellation, concurrency, low memory. `robolectric.properties` sdk=34 dead (all tests override to 33). androidTest suite commented out in CI.

---

## 19. CI/CD

(As in the sub-report.) test.yml always-passing static analysis (subshell counter bug at scripts/static_analysis.sh:13,26,44,52,60,76-93), warn-only OOM grep, `continue-on-error: true` playstore-release-check:145, instrumented tests commented out (lines 160-205), cache key ignores gradle.properties/wrapper. deploy.yml grants unused `actions: write`; Play+Indus uploads both `continue-on-error`; release step marks ❌ but workflow stays green. ensure-release-files.yml builds without signing secrets → silent failures, still green; mutates checkout. build-release.yml version inputs ignored. manage-releases keeps tags (F-Droid-safe). pin-release consistent. validate_metadata.sh not wired into GitHub workflows (fdroiddata CI is external).

---

## 20. Licensing & Docs Accuracy

(As in §5 + §18). README/F-Droid metadata/Play listing claim "digital signatures" (visual only). `licenses.html` lists PdfBox/Coil but should be verified against the full dependency set (POI/CameraX/uCrop/Glide/Room/DataStore/Tink-ish items listed partially — UNVERIFIED complete list). CHANGELOG stops at 1.3.210 vs version 1.3.225. metadata newest Builds entry 1.3.175 uses stale `VERSION_CODE/VERSION_NAME` gradleprops while the current build script reads only `APP_*` — that recipe would fail today. No `CurrentVersionCode` mismatch (225 matches gradle.properties). fastlane changelogs through ~211.

---

## 21. Dead Code (summary)

| Item | Confidence |
|---|---|
| `util/PdfTools.kt` (no call sites) | HIGH |
| `Screen.PdfViewerLegacy`, `Screen.fromFeatureTitle` | HIGH |
| `PdfViewerCapabilityTest`, `PdfViewerTest`, `PdfViewerScreenTest` bodies | HIGH |
| `robolectric.properties` | HIGH |
| `VERSION_CODE/VERSION_NAME` props | HIGH |
| Commented MuPDF deps, Sonatype snapshots | HIGH |
| `navigateToPdfTool` whitelist (silently drops tools) | HIGH (it is *used* but wrong) |
| `HistoryManager` validation gap | HIGH (behavioral) |
| `LanguageDataStore` KDoc vs DataStore name | MEDIUM |
| Coil+Glide duplication | HIGH |
| fdroid/opensource duplicate trees | HIGH |

---

## 22. Duplicate Code

- fdroid/opensource: OcrEngine/SmartDocScanner/ReviewManager/ReviewHelper identical except comments, plus duplicated `eng.traineddata` assets in both flavor dirs (~2×15-20 MB per flavor build variant).
- Every tool screen re-implements: file picker launcher + coroutine scope + progress state + result dialog wiring (SplitScreen, CompressScreen, RotateScreen, WatermarkScreen, …). Utility would be a `ToolScaffold` composable.
- `ImageProcessor`/`CropHelper`/`CustomUCropActivity`/uCrop config duplicated across ScanToPdf and ImageTools flows.
- DataStore patterns re-implemented 4× (ThemeManager, LanguageDataStore, ToolSettingsStore, ReviewPreferences uses SharedPreferences instead).

---

## 23. Documentation Accuracy

| Claim | Reality |
|---|---|
| README: "Sign PDF — Add digital signatures" | Visual stamp only (PdfSigner KDoc admits) |
| README: "Flatten PDF — Make forms and annotations permanent" | `PdfFlattener` does this; dead `PdfTools.flattenAndSavePdf` copies bytes |
| README/privacy: "completely offline, no data transmitted" | True for app; Play flavor's ML Kit scanner/OCR can fetch models; URL→PDF by definition remote; Indus upload is CI-side |
| PdfScanner KDoc: "auto-crop simulation" | Not implemented |
| PdfMetadataManager KDoc: "Remove all metadata" | XMP remains |
| PdfOcrProcessor KDoc implies measured confidence | Hardcoded 0.85 |
| `LanguageDataStore` KDoc: "same DataStore as ThemeManager" | Different file names |
| CHANGELOG top 1.3.210 | Actual 1.3.225 |
| metadata Newest Builds 1.3.175 recipe | Uses stale gradleprops names — would not build today |
| `AGENTS.md` claim: `gradle.properties` holds `APP_VERSION_CODE/NAME` | True, but legacy `VERSION_CODE/NAME` also present |

---

## 24. Feature Matrix

| Feature | UI | Impl | Nav | Tests | Flavors | Issue | Status |
|---|---|---|---|---|---|---|---|
| Merge | MergeScreen+VM | PdfMerger | ok | real | all | | COMPLETE |
| Split | SplitScreen | PdfSplitter | ok | none | all | no cancel | COMPLETE |
| Compress | CompressScreen | PdfCompressor | ok | real | all | races | PARTIAL |
| Organize/Reorder | 2 screens | PdfOrganizer | ok | none | all | | COMPLETE |
| Rotate | RotateScreen | PdfRotator | ok | real | all | | COMPLETE |
| Extract pages | ExtractScreen | PdfSplitter | ok | none | all | | COMPLETE |
| Watermark | WatermarkScreen+VM | PdfWatermarker | ok (route drift) | none | all | heap load | COMPLETE |
| Page numbers | PageNumberScreen | PdfPageNumberer | ok | none | all | | COMPLETE |
| Metadata | MetadataScreen | PdfMetadataManager | ok | none | all | XMP kept | PARTIAL |
| Flatten | FlattenScreen+VM | PdfFlattener | ok | real | all | wrong count | PARTIAL |
| Lock | SecurityScreen | PdfSecurityManager | ok | none | all | owner==user pw | PARTIAL |
| Unlock | UnlockScreen | PdfUnlocker | ok | none | all | error sniff | PARTIAL |
| Repair | RepairScreen | PdfRepairer | ok | none | all | | COMPLETE |
| OCR→PDF | OcrScreen+VM | PdfOcrProcessor | ok | none | play: ML Kit; fdroid/os: Tesseract | confidence fake | COMPLETE w/ caveats |
| Scan to PDF | ScanToPdfScreen+VM | PdfScanner+CameraX | ok | none | all (stub fdroid/os) | auto-crop fake claim | COMPLETE |
| DOCX viewer | DocxViewerScreen+VM | OfficeConverter+WebView | ok | none | all | base64 memory | PARTIAL |
| DOCX→PDF | DocToPdfScreen+VM | OfficeConverter / WebViewDocx | ok | none | all | legacy .doc message dead-ends | PARTIAL |
| XLSX/PPTX→PDF | ConvertScreen | OfficeConverter | unverified | none | all | no cancel/progress; OOM | UNVERIFIED |
| HTML→PDF | HtmlToPdfScreen | HtmlToPdfConverter | ok | none | all | WebView leak | PARTIAL |
| URL→PDF | HtmlToPdfScreen | loadUrl | gated HAS_NETWORK_URL_TO_PDF | none | playstore | **no INTERNET permission** | BROKEN/UNVERIFIED |
| Images→PDF | ConvertScreen | ImageConverter | ok | none | all | | COMPLETE |
| PDF→Images | PdfToImageScreen | ImageConverter | ok | none | all | RGB_565 lossy | COMPLETE |
| Sign | SignPdfScreen+VM | PdfSigner | ok | none | all | visual only | PARTIAL |
| Redact | none | PdfRedactor | no UI | none | all | stub + visual-only | DEAD/LEGACY |
| Annotations | AnnotationScreen+VM | PdfAnnotator | ok | androidTest only | all | | COMPLETE |
| Fill forms | FillFormsScreen+VM | PdfFormFiller | ok | real | all | | COMPLETE |
| PDF viewer | PdfViewerScreen+VM | android.graphics.pdf.PdfRenderer + PDFBox annotate | ok | empty tests | all | 2491-LOC screen | COMPLETE |
| Extract text | ExtractTextScreen | TextExtractor | ok | none | all | | COMPLETE |
| Imposition | PrintImpositionStudio+VM | ImpositionEngine | ok | cut-stack | all | lossless 3×DPI OOM | PARTIAL |
| Image tools | ImageToolsScreen | ImageProcessor | ok | none | all | | COMPLETE |
| Recents/history | FilesScreen/HistorySidebar | SafUriManager/HistoryManager | ok | conversion-only | all | dual backends | PARTIAL |
| Review prompt | ReviewIntegration | ReviewManager flavors | auto | real | per-flavor | logcat leak | COMPLETE |
| Print | via FileOpener/PrintUtils | Android PrintManager | ok | real (invalid URI) | all | | COMPLETE |

---

## 25. Critical Findings

### [CRITICAL] Deploy workflow exfiltrates signing keystore to a third party
File: `.github/workflows/deploy.yml` lines 246-253. Component: CI/CD.
Evidence: `curl -F "file=@keystore.jks" -F "key_password=$KEY_PASSWORD" -F "key_alias=$KEY_ALIAS" -F "store_password=$KEYSTORE_PASSWORD" developer-api.indusappstore.com`.
Problem: the app's release signing key is transmitted to an external SaaS on every release.
Impact: anyone with the key can produce an APK accepted as a Play-signed update.
Confidence: HIGH.
Recommended future action: upload the pre-signed APK to Indus; rotate the current key.

### [CRITICAL] Redaction is a painted rectangle; its "secure" flag silently does nothing
File: `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfRedactor.kt` lines 41-44, 66, 137-141, 212-232.
Component: PDF engine.
Evidence: `redactAreas(..., flattenAfterRedaction=true)` runs an empty `if` with comment "For now, we'll skip this"; `redactText` returns `UnsupportedOperationException`; KDoc admits visual-only; no screen references `PdfRedactor`.
Problem: surface data remains recoverable from the content stream; no caller can request real redaction.
Impact: privacy breach when users redact documents they publish.
Confidence: HIGH.
Recommended future action: fail loudly on `flattenAfterRedaction=true`, implement content-stream rewrite or rasterize-replace, wire the UI.

### [HIGH] "Digital signatures" are visual bitmaps
File: `PdfSigner.kt:89-92` vs README.md:86, metadata description.
Problem: user trust/legal overclaim.
Impact: liability.
Confidence: HIGH.
Recommended future action: reword to "visual signature"; real CMS only if implemented.

### [HIGH] URL→PDF has no INTERNET permission
File: `AndroidManifest.xml`, `HtmlToPdfConverter.kt:52,96`, `build.gradle.kts` playstore block.
Problem: Play-flavor flag `HAS_NETWORK_URL_TO_PDF=true` but manifest lacks `INTERNET`; only transitive ML Kit scanner deps may supply it.
Impact: broken feature in Play builds or undocumented permission source.
Confidence: HIGH on the manifest; MEDIUM on end-user impact (UNVERIFIED without build).

### [HIGH] OCR confidence hardcoded 0.85
File: `PdfOcrProcessor.kt:210`. Confidence: HIGH. Future: real per-page confidence or drop the field.

### [HIGH] `PdfTools.flattenAndSavePdf` byte-copies; temp files leak
File: `util/PdfTools.kt:199-213,46-58,97-100`. Confidence: HIGH. Future: delete/delegate + finally cleanup.

### [HIGH] Play-flavor OCR/scanner runtime model downloads contradict "fully offline"
File: `SmartDocScanner.kt` (playstore), `OcrEngine.kt` (playstore), privacy-policy.html.
Problem: Play Services can fetch Google's own models/scanner UI over network.
Confidence: MEDIUM (model downloads occur on first use; UNVERIFIED exact timing).

### [MEDIUM] `PdfMetadataManager.removeMetadata` leaves XMP
File: `PdfMetadataManager.kt:162,186`.

### [MEDIUM] FileProvider paths overly broad
File: `res/xml/file_paths.xml`.

### [MEDIUM] Backup includes everything but cache
File: `res/xml/backup_rules.xml`, `data_extraction_rules.xml`.

### [MEDIUM] Review logger writes usage to logcat
File: `ReviewLogger.kt:16`.

### [MEDIUM] WebView `allowFileAccess+domStorage` on asset-only view
File: `WebViewDocxToPdfConverter.kt` (renderAndCapture), DocxViewerScreen.

### [MEDIUM] `ensure-release-files.yml` silently fails every build; `test.yml` static analysis always passes
Files: `.github/workflows/ensure-release-files.yml`, `scripts/static_analysis.sh`, `.github/workflows/test.yml`.

### [MEDIUM] History URIs never validated / no persistable permission on history outputs
File: `HistoryManager.kt`.

### [LOW] `BaseVariantOutputImpl` internal API; `multiDexEnabled` unnecessary; `requestLegacyExternalStorage` no-op; stale `androidx.pdf` overrideLibrary; legacy `VERSION_CODE/NAME` props; stale `VERSION_CODE` gradleprops in metadata 1.3.175 recipe; CHANGELOG behind version.

---

## 26. High-Priority Findings (summary table)

| Priority | Finding | Area | Severity | Evidence | Action |
|---|---|---|---|---|---|
| P0 | Signing key exfiltrated to Indus | CI/CD | CRITICAL | deploy.yml:246-253 | rotate key, upload signed APK |
| P0 | Redaction stub + no-op flatten + extractable content | PDF engine | CRITICAL | PdfRedactor.kt:137-141,212-232 | implement or remove the feature |
| P1 | "Sign PDF" overclaimed (visual stamp) | Docs/feature | HIGH | PdfSigner.kt:89-92 | reword README/metadata |
| P1 | URL→PDF lacks INTERNET permission | Build | HIGH | AndroidManifest.xml | declare permission or remove flag |
| P1 | OCR confidence hardcoded | OCR | HIGH | PdfOcrProcessor.kt:210 | real confidence or drop |
| P1 | `PdfTools.flattenAndSavePdf` is a copy + leaks temp files | Util | HIGH | PdfTools.kt:199-213 | delete or delegate |
| P1 | `static_analysis.sh` always passes; release-check `continue-on-error` | CI | MEDIUM | test.yml:145 | fix subshell counter, drop continue-on-error |
| P1 | Missing/empty tests for redaction, signature, DOCX, WebView, OCR parity | Tests | MEDIUM | §18 | add behavioral tests |
| P2 | 15+ operations lack cancellation | PDF engine | MEDIUM | §14 | add ensureActive/withTimeout |
| P2 | Bare-heap `PDDocument.load` in 10+ classes | Performance | MEDIUM | §17 | MemoryUsageSetting.setupTempFileOnly |
| P2 | FileProvider paths too broad | Security | MEDIUM | file_paths.xml | narrow to shared_files |
| P2 | Backup includes history/prefs/DB | Security | MEDIUM | backup_rules.xml | exclude app-specific dirs |
| P2 | Room v1 without migrations | Data | MEDIUM | AppDatabase.kt:8 | add schema export + migrations |
| P2 | Dual recent-file backends | Data | MEDIUM | HistoryManager vs SafUriManager | unify or validate |
| P2 | ImpositionPdfExporter lossless 3×DPI, no cap | Performance | MEDIUM | ImpositionPdfExporter.kt:48,69-74 | DPI cap like OcrDpiUtil |
| P2 | PPTX converter holds all slide bitmaps | Performance | MEDIUM | OfficeConverter.kt:1003,1203 | stream per slide |
| P2 | CHANGELOG/metadata version drift | Docs | LOW | CHANGELOG.md, metadata yml | sync |
| P3 | Coil+Glide, fdroid/opensource dup trees, 15-screen copy-paste wiring, route drift, dead props/overrideLibrary | Hygiene | LOW | §21 | delete/dedupe/reword |

### P0 — Must resolve before release
1. Rotate the release keystore; stop sending it to Indus.
2. Either implement real redaction (content-stream rewrite or rasterize) or refuse to run with `flattenAfterRedaction=true` and advertise "visual overlay only" in the UI.

### P1 — Should resolve before major redesign
3. Reword "digital signature" claims.
4. Decide URL→PDF: declare INTERNET in the playstore flavor only (add a manifest snippet or a playstore manifest merge) or drop the flag.
5. Replace hardcoded OCR confidence with measured values.
6. Delete `util/PdfTools.kt` and reword `PdfScanner`'s KDoc.
7. Fix `scripts/static_analysis.sh` subshell counters; remove `continue-on-error: true` from the release check; stop swallowing build failures in `ensure-release-files.yml`.
8. Declare `INTERNET` only where needed and add the missing behavioral tests listed in §18 (redaction, signature, DOCX→PDF, WebView security, OCR parity, cancellation).

### P2 — Refactor/improve later
9. Cancellation (`ensureActive`) across the 15+ operations listed in §14.
10. Consistent `MemoryUsageSetting` usage; DPI caps in ImpositionPdfExporter; stream PPTX slides.
11. Narrow FileProvider paths; scope backup exclusions; unify recent-file backends; add Room migrations.
12. Sync CHANGELOG/metadata gradleprops; replace owner==user password with distinct user password; stop sniffing `contains("password")` for auth-failure classification.

### P3 — Optional cleanup
13. Remove `PdfTools.kt`, `Screen.PdfViewerLegacy`, `fromFeatureTitle`, dead `VERSION_CODE/NAME` props, stale `overrideLibrary`, commented MuPDF, sonatype snapshots, robolectric.properties sdk=34.
14. Deduplicate fdroid/opensource flavor dirs via a shared source set.
15. Extract a common `ToolScaffold` composable for the 15 screens that re-implement picker+progress+result wiring.
16. Drop one of Coil/Glide; swap `material-icons-extended` for core icons.

---

## 27. Medium-Priority Findings

See the §25 table (P2 column). Repeat-not-needed.

## 28. Low-Priority Findings

See the §25 table (P3 column).

## 29. Recommended Roadmap

(Consolidated from §25 P0–P3.)
1. Rotate keystore; drop Indus key upload.
2. Make redaction honest: fail loudly or implement content removal; remove dead `flattenAfterRedaction` path or implement it; consider wiring redaction into the UI only once it's real.
3. Reword README/F-Droid/Play metadata: "visual signature", "OCR confidence" claims removed, "flatten" only refers to the real `PdfFlattener`.
4. Decide URL→PDF: declare INTERNET in `app/src/playstore/AndroidManifest.xml` override or delete `HAS_NETWORK_URL_TO_PDF`.
5. Replace hardcoded OCR confidence.
6. Delete `util/PdfTools.kt`.
7. Fix CI: static-analysis counter, remove `continue-on-error` on release check, make `ensure-release-files.yml` fail loudly, wire `ci/fdroid/validate_metadata.sh` into test.yml.
8. Add the missing behavioral tests from §18, run androidTest instrumented tests in CI on an emulator shard.
9. Cancellation + `MemoryUsageSetting` consistency across operations.
10. Storage/security hygiene: FileProvider path narrowing, backup exclusions, Room migrations, owner-vs-user password, error-message-based classification replaced with typed errors.
11. Sync CHANGELOG + metadata; remove dead props and stale config; deduplicate flavor trees; extract a tool scaffold composable.

## 30. Files That Should Be Refactored

- `app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt` (2491 LOC) — split gesture/render/annotation/search into a separate composable set.
- `app/src/main/java/com/yourname/pdftoolkit/domain/operations/OfficeConverter.kt` (1939 LOC) — split into Docx/Xlsx/Pptx converters.
- `app/src/main/java/com/yourname/pdftoolkit/ui/screens/DocxViewerScreen.kt` (1259 LOC) and `ui/screens/PdfViewerViewModel.kt` (1200 LOC).
- `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfCompressor.kt` (1083 LOC) — single pipeline, typed strategy enum.
- `app/src/main/java/com/yourname/pdftoolkit/util/CacheManager.kt`, `FileManager.kt`, `OutputFolderManager.kt` — consolidate path heuristics and MediaStore behavior.
- `app/src/main/java/com/yourname/pdftoolkit/domain/operations/PdfRedactor.kt` — implement real redaction or delete.
- `app/src/main/java/com/yourname/pdftoolkit/util/PdfTools.kt` — delete.
- `.github/workflows/test.yml`, `deploy.yml`, `ensure-release-files.yml`, `scripts/static_analysis.sh` — see §19.

## 31. Files That Should NOT Be Touched Yet

- `app/src/main/java/com/yourname/pdftoolkit/domain/imposition/ImpositionEngine.kt` and `ImpositionModel.kt` — pure, tested (cut-stack), and correct for current scope; only its exporter needs a DPI cap.
- `app/src/main/java/com/yourname/pdftoolkit/ui/theme/*` — works; dark+dynamic color wired.
- `app/src/fdroid/...` and `app/src/opensource/...` flavor trees — isolation is correct; consolidate only after CI proves FOSS compliance.
- `app/src/main/assets/docx_viewer/*` — DOCX viewer works; changes require manual re-test against real DOCX files.
- `gradle/wrapper/gradle-wrapper.properties`, `settings.gradle.kts` — fine.
- `metadata/com.yourname.pdftoolkit.yml` `UpdateCheckData` — correct as-is.
- Tests that are real (`PdfFlattenerTest`, `PdfCompressorTest`, `PdfMergerTest`, `PdfRotatorTest`, `PdfFormFillerTest`, `PdfScannerTest`, `ImpositionEngineCutStackTest`, `PdfViewerViewModelTest`, `FillFormsScreenTest`, `ReviewSystemTest`) — keep.

## 32. Unknown / Unverified Items

- Full `./gradlew` build (no SDK / JDK 17 here) — dependency resolution, R8/proguard rules, and F-Droid flavor builds are UNVERIFIED.
- Whether URL→PDF actually fails in the Play flavor (transitive INTERNET from ML Kit scanner dep may mask it).
- Exact CVEs applicable to pdfbox-android 2.0.27.0 / POI 5.2.5 — needs a real dependency vulnerability scan.
- Per-font license correctness of bundled Carlito/Tinos fonts.
- Whether `mpu/licenses.html` lists every shipped dependency.
- Whether `CropHelper` is actually referenced (grep showed ScanToPdfScreen/ImageToolsScreen usage — treated as used, call sites partially verified).
- Whether `OcrScreen` works end-to-end in fdroid/opensource without Google Play Services (assumed by design; no instrumentation in CI).
- F-Droid `checkupdates` behavior against this repo — no local fdroidserver run; AGENTS.md says it is configured correctly, but only the `UpdateCheckData` string was verified statically.

---

## Final Prioritization

See §25 P0–P3 tables and the executive summary.

---

*End of audit. No source files were modified. Do not begin implementation until this report is reviewed.*
