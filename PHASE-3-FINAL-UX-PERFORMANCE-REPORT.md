# PHASE 3 — Final UX / Performance Report (Master Task)

## 1. Final UX behavior

Two states, exactly as specified:

- NORMAL view mode (default): PDF viewing, vertical scroll, zoom, search,
  navigation. No bottom editing toolbar, no Pan/Highlighter/Marker/Note/
  Eraser/color/drawer-arrow controls. Implemented by gating the entire
  `bottomBar` on `toolState is PdfTool.Edit` (previously the bar existed but
  hid via `AnimatedVisibility`; now it is not composed at all in normal mode).
- EDIT mode (top Pencil/Edit icon → `PdfTool.Edit`, tinted primary when
  active): bottom row shows Pan (always visible, never in the drawer) at the
  left and a drawer arrow at the right. Drawer hidden initially; left arrow
  (←) slides tools in right-to-left; arrow flips to → which slides them back.
  Tool-specific thickness/color controls are unchanged and independent of the
  arrow (shown when a tool is selected, as before).

## 2. Normal mode behavior

Clean bottom (maximum viewing space); tap toggles chrome; double-tap zoom;
search/top-bar/menu unchanged. No editing affordances composed.

## 3. Edit mode behavior

Pencil toggles `PdfTool.Edit`/`None`; exiting edit mode closes the drawer
(Pan tap also closes it). Annotation drawing only when a non-NONE tool is
selected (unchanged). Pan = select `AnnotationTool.NONE`.

## 4. Tool drawer behavior

`AnimatedVisibility` + horizontal slide, 200 ms tween (lightweight,
content-independent; page bitmaps are not recomposed by drawer state —
verified by inspection: drawer state is not a render key). Arrow semantics:
← opens, → closes (direction = action). Pan and thickness panel live outside
the drawer composable.

## 5. Existing text editing behavior

Tap text in Edit mode (Pan active) → nearest line located via the existing
`findClosestCharIndex` + new `findEditableLine` grouping → inline overlay
editor appears over the line with the original text, cursor placed near the
tap, keyboard opens on focus. Save calls
`PdfViewerViewModel.editPageText` → `PdfTextEditor.replaceInDocument` on the
open document (true byte removal, Ch.4 Phase-3 report) → page bitmap
invalidated → generation bump re-renders → toast confirms; failures toast the
reason. No long-press, no system popup, no separate Edit dialog as primary
(the old dialog was removed in favor of this flow).

## 6. CID/embedded font handling

Unchanged engine truth from Phase 3: single-byte text is replaced for real;
`PDType0Font`/CID matches fail explicitly with
"embedded CID font that cannot be safely replaced"; form-XObject/mixed
encodings fail explicitly; nothing is overdrawn-and-kept. The in-place editor
passes the tapped line's exact text, so the "not found" path triggers only
for genuinely unsupported content. No silent success, no duplicate text
underneath a reported success (blanking is verified by `blanked` count).

## 7. Scroll performance root cause

Device log (app PID 25421) showed 38–121 skipped frames during ordinary
paging plus 431/258-frame cold-start stalls. Code-proven main-thread costs:
(a) per-page-item `rememberInfiniteTransition` shimmer recomposing every
visible page every frame (fixed Phase 3: loading-only placeholder);
(b) full-resolution render + GPU upload + recomposition per entering page,
serialized on `documentMutex` (required: PdfRenderer is single-threaded),
with no prompt cancellation once queued (fixed Phase 3: `ensureActive()`
after lock acquisition; LazyColumn disposal already cancels `produceState`);
(c) 40+-parameter mega-composable causing 12 MB JIT compiles and expensive
recomposition (noted, not split: no safe boundary without behavioral risk).
GC events in the log trail the jank (effect, not proven cause).

## 8. Performance fixes

Phase 3 (already merged): shimmer scoping, render-queue cancellation,
stale-bitmap elimination. This task: drawer animation chosen as a 200 ms
tween on the chrome layer only (no page-tree recomposition — drawer state
is not referenced by any render key); no full-preload, no unbounded
bitmaps (LruCache maxMemory/8 retained), no quality reduction (full
RENDER_SCALE for visible pages), no sleeps/delays.

## 9. Rendering/cache changes

This task adds no new cache. Phase-3 document-generation keys (item keys,
`produceState` keys, page-text remember, selection reset) remain the
stale-page defense; `closeDocument()` eviction unchanged. Invariant holds:
bitmap identity = (document generation, page index).

## 10. Tests

- `PdfViewerPageCountTest` (6): page counts, reopen/generation, malformed→
  Error, bounds. All green in the latest full run except one transient
  (`seven_pages.pdf` vanished between verified creation and load — same
  worker-FS family, see §14).
- `PdfTextEditorTest` (5): all green repeatedly (true-replacement proof).
- Viewer awaits pump Robolectric's Main looper explicitly + 60 s withTimeout
  + 120 s JUnit timeouts (no CI hangs since).
- No FPS/scroll unit tests (would be fake); no Compose UI tests (no
  emulator in CI). Drawer/pan-visibility logic is plain conditional
  composition (inspectable, no test harness available).
- Suite: 92 tests, 89 green; 3 failures, all previously-seen transient
  family with file-missing signatures on untouched files
  (`testMixedPdfAndImageMerge`, `withCorrectPassword` partial-write
  misread as password error, `seven_pages` disappearance).

## 11. Real-device test results

NOT PERFORMED — no device access from this environment. The 30-item
manual checklist (items 1–30: open/scroll/zoom/pencil/drawer/settings/
tap-edit/save/reopen/5× fling both directions/second-PDF stalls/OOM/GC)
is explicitly unexecuted. Logcat evidence not captured post-fix.

## 12. Before/after performance evidence

Before: log-documented 38–431 skipped frames + code-proven per-frame
recomposition storm and un-cancellable queued renders. After: storm and
queue causes removed by construction; quantitative before/after
UNMEASURED (no device). No FPS claimed.

## 13. Known limitations

- Replacement typography approximated (Helvetica/size-matched).
- Single-byte encodings only; CID/form text rejected explicitly.
- Stream-order matching; multi-column taps may report "not found".
- Unicode PDF passwords rejected by the engine (documented, untestable).
- Worker-FS transient flakes persist (bounded, loud, documented).
- No release signing secrets → release job fails fast by design.

## 14. Remaining issues

- Real-device verification outstanding (mandatory before PASS).
- Suite-wide transient FS flake (environmental).
- Mega-composable decomposition deferred (needs guarding tests first).
- No emulator → androidTest never runs.

---

PHASE 3 UX/PERFORMANCE STATUS: PARTIAL
