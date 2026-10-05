# PHASE 1B — Flaky Test Investigation Report

## 1. Test under investigation

Primary: `PdfCompressorTest::testCompressPdf_basic`
(`app/src/test/java/com/hmx/toolkit/domain/operations/PdfCompressorTest.kt`)

```kotlin
val result = pdfCompressor.compressPdf(context, Uri.fromFile(inputFile), outputStream, MEDIUM)
outputStream.close()
if (result.isFailure) { println(...) }          // diagnostic only
assertTrue("Compression should succeed", result.isSuccess)   // line 68
val compressionResult = result.getOrNull()!!
assertTrue(outputFile.exists())                             // line 72
val outputDoc = PDDocument.load(outputFile)
assertEquals(1, outputDoc.numberOfPages)
```

Secondary (same flake class, observed across runs): `PdfCompressorIsolationTest`
sibling/fallback tests, `PdfMergerTest::testMixedPdfAndImageMerge`,
`PdfRotatorTest::testRotateWithNegativeOrModuloDegrees / testRotateAllPages`,
`PdfCompressorTest::testCompressPdfToTargetSize_Failure`.

## 2. Reproduction results

Local reproduction: NOT attempted — development device is resource-limited
(100 MB RAM class) and local Gradle builds are prohibited by explicit user
instruction. CI runs serve as the repeated-trial record instead.

CI trial history (`./gradlew testFdroidDebugUnitTest`, fdroidDebug, Robolectric):

| Run | Commit | Result | Failing tests |
|-----|--------|--------|---------------|
| 37184964308 | 6f00398 | FAIL (2/80) | PrintUtilsTest ×2 (ShadowContentResolver — fixed, deterministic cause) |
| 37185575923 | 9ca4a92 | FAIL (2/80) | testCompressPdf_basic, testCompressPdfToTargetSize_Failure |
| 37185575923 rerun | 9ca4a92 (identical) | SUCCESS | none — same code flipped fail→pass |
| 37222842150 | dde42fd (package migration) | FAIL (2/80) | testMixedPdfAndImageMerge, testRotateWithNegativeOrModuloDegrees |
| 37228953804 | 26138ae (1A workspaces) | FAIL (2/82) | isolation/sibling marker FileNotFound, basic (isSuccess) |
| 37230281599 | 64d0dc0 (mkdirs check) | FAIL (2/82) | isolation/fallback ex-type, basic (isSuccess) |
| 37231900832 | bda7b6a (diagnostics) | FAIL (2/82) | isolation/fallback ex-type, testMixedPdfAndImageMerge |
| 37233997082 | cb2336a (setup assert) | FAIL (1/82) | basic at line 72 `assertTrue(outputFile.exists())` — all 6 isolation tests green |

Pattern: every run fails a DIFFERENT 1–2 tests out of 80–82; identical-shape
tests pass and fail interchangeably, including within the same run
(e.g. old strict-failure test passes while new fallback test fails on the
same strict call shape; `successful compression leaves no workspace behind`
passes while `testCompressPdf_basic` fails on the same compressPdf path).

## 3. Exact failure (latest run, 37233997082)

```
PdfCompressorTest > testCompressPdf_basic FAILED
    java.lang.AssertionError
        at ...PdfCompressorTest.kt:72   // assertTrue(outputFile.exists())
```

- Line 68 (`assertTrue("Compression should succeed", ...)`) PASSED.
- No `testCompressPdf_basic failed with:` stdout captured → `result.isSuccess`
  was true.
- So: `compressPdf` returned success, bytes were copied into the test's
  `FileOutputStream(test_output.pdf)`, the stream was closed, yet
  `File(cacheDir, "test_output.pdf").exists()` returned false microseconds
  later, single-threaded.

## 4. Evidence collected

For the "success-but-file-missing" paradox, every in-code explanation was
checked and excluded:

- Same `File` object backs both the stream and the `exists()` check — no
  path divergence possible.
- `FileOutputStream` construction creates the file; `close()` flushes. A
  `close()` exception would surface as an error, not this AssertionError.
- Production code between copy and return touches ONLY the operation-scoped
  workspace (`compress_cache/op_<millis>_<UUID>/`); the test output lives in
  cacheDir root and no code path references it (verified by grep).
- `CacheManager.clearPdfOperationsCache` (the app's own sweeper) uses
  non-recursive root listing for `*.pdf`/`temp_*` plus named subdirs — it
  cannot reach into `compress_cache/op_*`, and nothing invokes it mid-test
  under `@Config(manifest = NONE)` (plain `Application`, no `onCreate` sweep).
- No `@After`/`@AfterClass`, no `forkEvery`/`maxParallelForks` (Gradle defaults:
  sequential classes, sequential methods, one fork). No test deletes another
  test's files (grep verified; merger/rotator delete only their own).
- Test stdout shows no exception; job log shows no OOM/GC/low-memory markers.
- Zero production diff between the pass run (37231900832) and fail runs for
  this test — the flip-flop occurs with byte-identical production code
  (proven by the identical-commit rerun of 37185575923 flipping fail→success).

Earlier fixed (deterministic, NOT this flake): missing `PDFBoxResourceLoader`
init, font-AFM resources (`isIncludeAndroidResources`), Robolectric
un-emulatable content-provider URIs, viewer search/clearSearch race. Those
had stable signatures and stayed fixed. The remaining failures have no stable
signature.

## 5. Root-cause classification

**H. Flaky / nondeterministic behavior**, with **G. Environment/filesystem**
as the likely contributor (GitHub-hosted worker FS/timing under a suite that
renders 100–1000-page PDFs, rasterizes bitmaps, and hammers `/tmp` via
PDFBox `setupTempFileOnly()` scratch files).

## 6. Proven vs suspected causes

- PROVEN: the failure is not deterministic (identical-commit rerun flips;
  same-shape tests disagree within one run; zero-diff flips).
- PROVEN: production `PdfCompressor` logic is not implicated — the success
  path (copy → flush → success) provably executed (line 68 passed), and the
  1A workspace change cannot delete the test output (path-verified).
- PROVEN: tests were not weakened at any point (only assertion messages and
  cause-println diagnostics added; all behavioral asserts intact).
- SUSPECTED but UNPROVEN: transient filesystem visibility / resource pressure
  on the CI worker causing `exists()`/file-creation races. No OOM or FS error
  appears in logs, so this remains a hypothesis, not a finding.
- The exact micro-cause of `exists()==false-after-successful-close` is
  UNPROVEN with the evidence obtainable from CI logs alone.

## 7. Whether production code is implicated

No. Evidence against: (a) success was returned, meaning every production
step including workspace cleanup completed normally; (b) no production path
references the test output file; (c) flips occur with zero production diff;
(d) the Debug APK job (real `assembleOpensourceDebug` + `apksigner verify`)
is green in the same runs.

## 8. Whether test code is implicated

The test is not wrong — it asserts the correct contract (success ⇒ output
exists and parses with 1 page). It is a VICTIM of harness flakiness, not its
author (classification: neither B-test-bug nor C-obsolete). The two
diagnostic additions (failure-cause println, setup-exists assert) are
neutral instrumentation, not behavior changes.

## 9. Recommended future fix, if any

Do NOT touch production or weaken the test. Instead, in a future phase with
a capable machine:

1. Run `testCompressPdf_basic` in a loop locally
   (`./gradlew :app:testFdroidDebugUnitTest --tests
   "com.hmx.toolkit.domain.operations.PdfCompressorTest.testCompressPdf_basic"`)
   with `--info` to capture stdout, watching for the `exists()` flip and any
   accompanying FS exception.
2. If it reproduces locally, strace/large-loop the `FileOutputStream.close →
   exists()` window; check `/tmp` pressure and PDFBox scratch-file handling
   under `setupTempFileOnly()`.
3. If it never reproduces locally after N runs, quarantine-by-label is still
   FORBIDDEN by project rules — instead add a retry-free diagnostic (e.g.
   on `!exists()`, list parent dir + re-check once and print both) to convert
   the next CI occurrence into a proven cause.
4. Consider splitting the 80-test single-task run or adding
   `forkEvery` isolation ONLY as a diagnostic experiment, not as a fix.

## 10. Final status

**FLAKE CONFIRMED — ROOT CAUSE UNPROVEN**

- Production code: cleared by evidence.
- Test contract: correct, preserved, not weakened.
- Failing test: kept as-is (not deleted, skipped, quarantined, retried, or
  wrapped in `continue-on-error`/`|| true`).
- Suite remains red on this pre-existing flake; Phase 1A's own 6 isolation
  tests are green, and the release/debug-APK pipeline is unaffected.
