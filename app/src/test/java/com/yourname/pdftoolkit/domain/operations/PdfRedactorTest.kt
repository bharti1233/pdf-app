package com.yourname.pdftoolkit.domain.operations

import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class PdfRedactorTest {

    private lateinit var redactor: PdfRedactor
    private lateinit var tempDir: File

    @Before
    fun setup() {
        redactor = PdfRedactor()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
        tempDir = File(System.getProperty("java.io.tmpdir"), "pdf_redactor_test")
        if (!tempDir.exists()) tempDir.mkdirs()
    }

    private fun createPdf(path: String, pages: Int, secrets: List<String>): File {
        val file = File(tempDir, path)
        val doc = PDDocument()
        for (i in 0 until pages) {
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 24f)
                cs.newLineAtOffset(72f, 720f)
                cs.showText(secrets.getOrNull(i) ?: "page $i")
                cs.endText()
            }
        }
        doc.save(FileOutputStream(file))
        doc.close()
        return file
    }

    private fun extractText(file: File): String {
        val doc = PDDocument.load(file)
        return try {
            PDFTextStripper().getText(doc)
        } finally {
            doc.close()
        }
    }

    @Test
    fun `redacted text is not extractable from output`() = runBlocking {
        val src = createPdf("secret.pdf", 1, listOf("TOPSECRET"))
        val out = File(tempDir, "redacted.pdf")
        val ctx = RuntimeEnvironment.getApplication()

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            areas = listOf(
                RedactionArea(0, RectF(60f, 700f, 400f, 750f))
            )
        )

        assertTrue("redaction should succeed: ${result}", result.isSuccess)
        val text = extractText(out)
        assertFalse("secret must not be extractable", text.contains("TOPSECRET"))
    }

    @Test
    fun `output opens and preserves page count`() = runBlocking {
        val src = createPdf("multi.pdf", 2, listOf("TOPSECRETONE", "PUBLICPAGE"))
        val out = File(tempDir, "redacted2.pdf")
        val ctx = RuntimeEnvironment.getApplication()

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            areas = listOf(RedactionArea(0, RectF(60f, 700f, 400f, 750f)))
        )

        assertTrue(result.isSuccess)
        val doc = PDDocument.load(out)
        try {
            assertEquals(2, doc.numberOfPages)
        } finally {
            doc.close()
        }
    }

    @Test
    fun `redaction across multiple pages removes secret on each`() = runBlocking {
        val src = createPdf("secrets2.pdf", 2, listOf("SECRETA", "SECRETB"))
        val out = File(tempDir, "redacted3.pdf")
        val ctx = RuntimeEnvironment.getApplication()

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            areas = listOf(
                RedactionArea(0, RectF(60f, 700f, 400f, 750f)),
                RedactionArea(1, RectF(60f, 700f, 400f, 750f))
            )
        )

        assertTrue(result.isSuccess)
        val text = extractText(out)
        assertFalse(text.contains("SECRETA"))
        assertFalse(text.contains("SECRETB"))
    }

    @Test
    fun `unaffected page text is preserved`() = runBlocking {
        val src = createPdf("mixed.pdf", 2, listOf("SECRETPAGE", "PUBLICPAGE"))
        val out = File(tempDir, "redacted4.pdf")
        val ctx = RuntimeEnvironment.getApplication()

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            areas = listOf(RedactionArea(0, RectF(60f, 700f, 400f, 750f)))
        )

        assertTrue(result.isSuccess)
        val text = extractText(out)
        assertFalse(text.contains("SECRETPAGE"))
        assertTrue("unaffected page text must survive", text.contains("PUBLICPAGE"))
    }

    @Test
    fun `multiple regions on same page all removed`() = runBlocking {
        val src = createPdf("secret3.pdf", 1, listOf("TOPHIDDEN"))
        val out = File(tempDir, "redacted5.pdf")
        val ctx = RuntimeEnvironment.getApplication()

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            areas = listOf(
                RedactionArea(0, RectF(60f, 700f, 200f, 750f)),
                RedactionArea(0, RectF(200f, 700f, 400f, 750f))
            )
        )

        assertTrue(result.isSuccess)
        assertFalse(extractText(out).contains("TOPHIDDEN"))
    }

    @Test
    fun `malformed input fails explicitly`() = runBlocking {
        val bad = File(tempDir, "bad.pdf")
        bad.writeText("this is not a pdf")
        val out = File(tempDir, "redacted_bad.pdf")
        val ctx = RuntimeEnvironment.getApplication()

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(bad),
            outputStream = FileOutputStream(out),
            areas = listOf(RedactionArea(0, RectF(0f, 0f, 10f, 10f)))
        )

        assertTrue("malformed input must fail", result.isFailure)
    }

    @Test
    fun `successful operation leaves no stray files in cacheDir`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheBefore = ctx.cacheDir.listFiles()?.toSet() ?: emptySet()

        val src = createPdf("ok.pdf", 1, listOf("SECRET"))
        val out = File(tempDir, "redacted_ok.pdf")

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            areas = listOf(RedactionArea(0, RectF(60f, 700f, 400f, 750f)))
        )

        assertTrue(result.isSuccess)
        val cacheAfter = ctx.cacheDir.listFiles()?.toSet() ?: emptySet()
        assertEquals("cache dir must be unchanged on success", cacheBefore, cacheAfter)
    }

    @Test
    fun `failure leaves no temp files in cacheDir`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val cacheBefore = ctx.cacheDir.listFiles()?.toSet() ?: emptySet()

        val bad = File(tempDir, "bad2.pdf")
        bad.writeText("not a pdf either")
        val out = File(tempDir, "redacted_bad2.pdf")

        val result = redactor.redactAreas(
            context = ctx,
            inputUri = Uri.fromFile(bad),
            outputStream = FileOutputStream(out),
            areas = listOf(RedactionArea(0, RectF(0f, 0f, 10f, 10f)))
        )

        assertTrue(result.isFailure)
        val cacheAfter = ctx.cacheDir.listFiles()?.toSet() ?: emptySet()
        assertEquals("cache dir must be unchanged on failure", cacheBefore, cacheAfter)
    }
}
