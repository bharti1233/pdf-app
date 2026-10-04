package com.hmx.toolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Proves compression temp files are operation-scoped:
 * each operation owns compress_cache/op_<id>/ and cleanup deletes only that dir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PdfCompressorIsolationTest {

    private lateinit var context: Context
    private lateinit var pdfCompressor: PdfCompressor

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        pdfCompressor = PdfCompressor()
        context.cacheDir.mkdirs()
    }

    private fun opDirs(): Set<String> {
        val base = File(context.cacheDir, "compress_cache")
        return base.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("op_") }
            ?.map { it.name }
            ?.toSet() ?: emptySet()
    }

    private fun createTestPdf(file: File) {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { content ->
            content.setNonStrokingColor(0, 0, 0)
            content.addRect(100f, 700f, 100f, 50f)
            content.fill()
        }
        doc.save(file)
        doc.close()
    }

    @Test
    fun `operation dirs are unique per operation`() {
        val a = pdfCompressor.newOperationDir(context)
        val b = pdfCompressor.newOperationDir(context)
        try {
            assertNotEquals("operation dirs must be unique", a.absolutePath, b.absolutePath)
            assertTrue(a.isDirectory)
            assertTrue(b.isDirectory)
        } finally {
            pdfCompressor.cleanupOperationDir(a)
            pdfCompressor.cleanupOperationDir(b)
        }
    }

    @Test
    fun `cleanup of one operation does not touch sibling operation`() {
        val dirA = pdfCompressor.newOperationDir(context)
        val dirB = pdfCompressor.newOperationDir(context)
        val markerA = File(dirA, "markerA.pdf").apply { writeText("a") }
        val markerB = File(dirB, "markerB.pdf").apply { writeText("b") }
        try {
            pdfCompressor.cleanupOperationDir(dirA)
            assertFalse("cleaned dir must be gone", dirA.exists())
            assertTrue("sibling dir must survive", dirB.exists())
            assertTrue("sibling file must survive", markerB.exists())
            assertEquals("b", markerB.readText())
            assertFalse(markerA.exists())
        } finally {
            pdfCompressor.cleanupOperationDir(dirB)
        }
    }

    @Test
    fun `successful compression leaves no operation workspace behind`() = runBlocking {
        val before = opDirs()
        val inputFile = File(context.cacheDir, "iso_input.pdf")
        createTestPdf(inputFile)
        val outputFile = File(context.cacheDir, "iso_output.pdf")
        val outputStream = FileOutputStream(outputFile)

        val result = pdfCompressor.compressPdf(
            context = context,
            inputUri = Uri.fromFile(inputFile),
            outputStream = outputStream,
            level = CompressionLevel.MEDIUM
        )
        outputStream.close()

        assertTrue("compression should succeed", result.isSuccess)
        val after = opDirs()
        assertEquals("no workspace must leak after success", before, after)
    }

    @Test
    fun `failed compression leaves no operation workspace behind`() = runBlocking {
        val before = opDirs()
        val missing = File(context.cacheDir, "iso_missing_${System.currentTimeMillis()}.pdf")
        if (missing.exists()) missing.delete()
        val outputFile = File(context.cacheDir, "iso_failed_output.pdf")
        val outputStream = FileOutputStream(outputFile)

        val result = pdfCompressor.compressPdf(
            context = context,
            inputUri = Uri.fromFile(missing),
            outputStream = outputStream,
            level = CompressionLevel.MEDIUM
        )
        outputStream.close()

        assertTrue("missing input must fail", result.isFailure)
        val after = opDirs()
        assertEquals("no workspace must leak after failure", before, after)
    }

    @Test
    fun `failed operation cleanup does not delete sibling workspace`() = runBlocking {
        val sibling = pdfCompressor.newOperationDir(context)
        val siblingMarker = File(sibling, "sibling.pdf").apply { writeText("keep") }
        try {
            val before = opDirs()
            assertTrue(before.contains(sibling.name))

            val missing = File(context.cacheDir, "iso_missing_sibling.pdf")
            if (missing.exists()) missing.delete()
            val outputFile = File(context.cacheDir, "iso_failed_sibling_output.pdf")
            FileOutputStream(outputFile).use { out ->
                val result = pdfCompressor.compressPdf(
                    context = context,
                    inputUri = Uri.fromFile(missing),
                    outputStream = out,
                    level = CompressionLevel.MEDIUM
                )
                assertTrue(result.isFailure)
            }

            assertTrue("sibling workspace must survive failed op", sibling.exists())
            assertTrue(siblingMarker.exists())
        } finally {
            pdfCompressor.cleanupOperationDir(sibling)
        }
    }

    @Test
    fun `strict target failure preserves fallback file outside workspace`() = runBlocking {
        val inputFile = File(context.cacheDir, "iso_strict_input.pdf")
        createTestPdf(inputFile)
        // Diagnostic, not weaker: if the harness loses our input file, fail here
        // with a clear message instead of masquerading as a contract failure below.
        assertTrue("test input must exist after creation", inputFile.exists() && inputFile.length() > 0)
        val outputFile = File(context.cacheDir, "iso_strict_output.pdf")
        val outputStream = FileOutputStream(outputFile)

        val result = pdfCompressor.compressPdfToTargetSizeStrict(
            context = context,
            inputUri = Uri.fromFile(inputFile),
            outputStream = outputStream,
            targetSizeBytes = 10L
        )
        outputStream.close()

        assertTrue("impossible target must fail", result.isFailure)
        val ex = result.exceptionOrNull()
        if (ex !is TargetSizeNotReachedException) {
            println("strict target-size failed unexpectedly with: $ex")
        }
        assertTrue("expected TargetSizeNotReachedException but was: $ex", ex is TargetSizeNotReachedException)
        val fallback = (ex as TargetSizeNotReachedException).fallbackFile
        try {
            assertTrue("fallback file must survive workspace cleanup", fallback.exists())
            assertTrue(fallback.length() > 0)
        } finally {
            // Caller owns the preserved fallback; do not litter cache.
            try { fallback.delete() } catch (_: Exception) { }
        }
    }
}
