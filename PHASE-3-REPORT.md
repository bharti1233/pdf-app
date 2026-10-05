# PHASE 3 — PDF Viewer, Text Editing & Performance Report

## 1. Page-count root cause

Investigated a possible viewer-shows-wrong-count/index complaint. Findings:

- Page count has ONE authoritative source: `PDDocument.numberOfPages` read at
  load time into `PdfViewerUiState.Loaded(totalPages)`. Screen, indicator
  (`"$currentPage / $totalPages"`), `items(count = totalPages)`, go-to-page
  validation, and `loadPage` bounds all derive from it. No second source, no
  hardcoded counts. Zero-based internally, one-based visually
  (`firstVisibleItemIndex + 1`), consistently.
- REAL bugs found around it (all fixed, all proven by inspection):
  1. `produceState(key1 = pageIndex)` + `items(key = { it })` reused the
     previous document's bitmaps after opening another PDF with the same page
     count (PDF B page 4 displayed PDF A page 4). Fixed with a
     `documentGeneration` counter (VM `StateFlow`, bumped per `loadPdf`)
     included in item keys, `produceState` keys, and page-text `remember`.
  2. Screen-level text selection (`selectPageIndex/...`) survived document
     switches, so copy/edit actions could target the old document's text.
     Fixed with a `LaunchedEffect(documentGeneration)` reset.
  3. VM `_currentPage` / `updateCurrentPage()` was write-only dead state (zero
     callers, zero readers); the screen's derived `currentPage` is the only
     live source. Removed.
- No off-by-one in the count pipeline itself; count refreshes on every
  `loadPdf` (verified by reopen test asserting 5 → 3).

## 2. Page-count fix

`PdfViewerViewModel.kt`: added `documentGeneration`, removed dead page state,
`ensureActive()` inside the render lock so disposed pages abort promptly.
`PdfViewerScreen.kt`: generation-keyed items/produceState/text-state,
selection reset on document change.

## 3. Existing-text-editing capability analysis

Available: PDFBox `TextPosition` glyph stream with coordinates/fonts/sizes,
page content-stream APPEND writes (already used for annotations), standard
single-byte fonts. NOT available in the port: `contentstream.PDFStreamParser`
(package absent — verified against port source; the parser lives in
`pdfparser.PDFStreamParser`, confirmed via port source + CI compile).
True removal therefore = blanking matched bytes inside `Tj`/`TJ` string
operands (spaces preserve layout), then drawing the replacement at the
recorded position/size. CID/multi-byte fonts and form-XObject text are
excluded with explicit failures (byte↔glyph mapping would be unsound).

## 4. Text-editing implementation/limitation

New `PdfTextEditor` (`replaceText` Uri wrapper + `replaceInDocument` for the
viewer's in-memory doc — no temp files, no reload round-trip). Original bytes
are gone from the saved stream (proven by extraction tests), replacement is
drawn with recorded size in Helvetica/black (style approximation documented).
`PdfViewerViewModel.editPageText` applies it under the document mutex,
invalidates the page bitmap, bumps generation for re-render; unsaved until
the normal save flow. UI: selection menu gains an Edit action → dialog →
toast; failures show the reason. Limitations: single-byte text only, stream
order matching (multi-column selections may report "not found"), longer
replacements may overprint, no cryptographic anything (visual text only).

## 5. UI changes

- Selection menu: Copy + new Edit-text action (existing divider pattern).
- New edit-text dialog (title/hint/save/cancel strings added).
- Annotation toolbar row made horizontally scrollable (narrow screens).
- Shimmer placeholder scoped to loading branch (was per-item infinite
  animation — see §6). No proprietary UI copied (no reference files exist in
  repo); app keeps its own Material3 identity. Home/Settings untouched.

## 6. Performance root cause

Device log (`toolkit.log`, app PID 25421) evidence:
- 13:27:59 skip 431 + 13:28:02 skip 258 + 1.1 s GC pause: cold-start Compose
  first composition (ToolsScreen/nav graph), not scrolling.
- 13:28:21/24 skip 39/48, 13:29:04 skip 51, 13:30:09/28 skip 44/39: genuine
  scroll jank while paging a 5-page then 3-page PDF
  (`PdfViewerVM: Loaded PDF with 5 pages` 13:28:13,
  `Loaded PDF with 3 pages` 13:29:57).
- JIT: `PdfPageWithAnnotations` compile allocates 12 MB (40+ parameter
  mega-composable → slow first composition, expensive recomposition).
- GC events exist but trail the jank (effect, not proven cause).
- Code-proven main-thread costs during scroll: (a) per-item
  `rememberInfiniteTransition` recomposing every visible page every frame;
  (b) full-resolution render per newly visible page with GPU upload +
  recomposition on completion; (c) renders serialize on `documentMutex`
  (required: `PdfRenderer` is single-threaded) so fast scrolls queue work.

## 7. Performance changes

- Shimmer animation hoisted into a loading-only `PageLoadingPlaceholder`
  composable (infinite transition no longer composed for loaded pages).
- `ensureActive()` after acquiring the render mutex so pages that left the
  viewport abort instead of rendering.
- `produceState` disposal (already present) + mutex-bounded concurrency (1,
  required by renderer thread-safety) + LruCache (maxMemory/8) retained;
  no quality reduction (full RENDER_SCALE kept for visible pages).
- Toolbar scrollability (layout, not perf, but same area).

## 8. Rendering/cache changes

- Document-generation keys (see §1) — fixes wrong-document bitmaps.
- `closeDocument()` already evicts the bitmap cache on switches (verified,
  unchanged).
- No unbounded caches introduced; no full preload (LazyColumn default
  viewport behavior kept).

## 9. Tests added/changed

- `PdfViewerPageCountTest` (6): 1/3/7-page counts, reopen updates count +
  generation, malformed → Error, loadPage bounds with/without document.
- `PdfTextEditorTest` (5): replacement removes original + inserts new (with
  untouched-text assertions), reopen page count, missing-text/invalid-page/
  malformed explicit failures.
- Viewer awaits pump Robolectric's Main looper explicitly (deterministic
  settlement) with 60 s `withTimeout` + 120 s JUnit timeouts as hang guards.
- Fixture writes verify persistence with bounded loud retry (documented
  environmental tolerance; behavioral asserts stay single-shot).

## 10. Device verification

Not performed on-device (no device access in this environment; low-RAM
constraint). Device-log analysis (§6) substitutes for measurement; no FPS
numbers are claimed.

## 11. Before/after performance evidence

Before: log shows repeated 38–121-frame skips during ordinary paging plus a
per-item infinite recomposition storm (code-proven) and renders that cannot
cancel once queued behind the mutex. After: shimmer recomposition eliminated
for loaded pages; queued renders abort on disposal; stale-document renders
eliminated. Rerun-on-device measurement still required for quantitative
before/after (UNVERIFIED — see §12).

## 12. Known limitations

- Replacement typography approximated (Helvetica/size-matched, black).
- Single-byte encodings only; CID/form-XObject text rejected explicitly.
- Stream-order matching; multi-column selections may fail with guidance.
- Quantitative scroll improvement UNVERIFIED without device measurement.
- Unit suite red intermittently on the documented worker-FS transient
  (create-then-invisible files; see Phase 1B) — currently 2 failures this
  run: one own-transient (`seven_pages.pdf` vanished post-verification),
  one pre-existing (`testCompressPdfToTargetSize_Failure`, untouched files).
- Unicode PDF passwords rejected by the PDF engine itself (documented in
  code; untestable case removed, not weakened).

## 13. Remaining issues

- Flaky-worker FS transient (tracks across phases; bounded by timeouts +
  loud failures, never hidden).
- Missing signing secrets still fail the release job fast by design.
- No emulator: androidTest never runs; Compose UI paths verified by
  compilation + unit tests only.

---

PHASE 3 STATUS: PARTIAL
Page-count result: PASS (single source verified + 3 staleness bugs fixed + tests)
Text-editing result: PASS (true replacement implemented, proven by extraction tests)
Performance result: PARTIAL (root causes fixed by inspection+log; device measurement unverified)
