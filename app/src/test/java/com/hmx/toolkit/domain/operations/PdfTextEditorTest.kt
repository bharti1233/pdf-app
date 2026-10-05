package com.hmx.toolkit.domain.operations

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Behavioral tests for true text replacement: the original bytes must be gone
 * from the saved document (not merely covered), and the new text extractable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PdfTextEditorTest {

    private lateinit var context: Context
    private lateinit var editor: PdfTextEditor

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        editor = PdfTextEditor()
        context.cacheDir.mkdirs()
    }

    private fun createTextPdf(path: String, lines: List<String>): File {
        val file = File(context.cacheDir, path)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(72f, 720f)
                lines.forEachIndexed { i, line ->
                    if (i > 0) {
                        cs.newLineAtOffset(0f, -20f)
                    }
                    cs.showText(line)
                }
                cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    private fun extractText(file: File): String {
        PDDocument.load(file).use { doc ->
            return PDFTextStripper().getText(doc)
        }
    }

    @Test
    fun `replacement removes original and inserts new text`() = runBlocking {
        val src = createTextPdf("edit_src.pdf", listOf("Hello World", "Second line"))
        val out = File(context.cacheDir, "edit_out.pdf")
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val result = editor.replaceText(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            pageIndex = 0,
            oldText = "World",
            newText = "PdfToolkit"
        )

        assertTrue("edit should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(5, result.getOrNull()!!.charsReplaced)

        val text = extractText(out)
        assertTrue("new text must be extractable, got: $text", text.contains("PdfToolkit"))
        assertFalse("original must be gone, got: $text", text.contains("World"))
        assertTrue("untouched text must survive, got: $text", text.contains("Hello"))
        assertTrue("other lines must survive, got: $text", text.contains("Second line"))
    }

    @Test
    fun `output reopens with correct page count`() = runBlocking {
        val src = createTextPdf("edit_pages.pdf", listOf("Alpha Beta"))
        val out = File(context.cacheDir, "edit_pages_out.pdf")
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val result = editor.replaceText(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            pageIndex = 0,
            oldText = "Beta",
            newText = "Gamma"
        )

        assertTrue(result.isSuccess)
        PDDocument.load(out).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `missing text fails explicitly`() = runBlocking {
        val src = createTextPdf("edit_missing.pdf", listOf("Hello World"))
        val out = File(context.cacheDir, "edit_missing_out.pdf")
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val result = editor.replaceText(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            pageIndex = 0,
            oldText = "NotPresent",
            newText = "X"
        )

        assertTrue("absent text must fail, not fake success", result.isFailure)
    }

    @Test
    fun `invalid page index fails explicitly`() = runBlocking {
        val src = createTextPdf("edit_badpage.pdf", listOf("Hello"))
        val out = File(context.cacheDir, "edit_badpage_out.pdf")
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val result = editor.replaceText(
            context = ctx,
            inputUri = Uri.fromFile(src),
            outputStream = FileOutputStream(out),
            pageIndex = 5,
            oldText = "Hello",
            newText = "Hi"
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed input fails explicitly`() = runBlocking {
        val bad = File(context.cacheDir, "edit_bad.pdf").apply { writeText("not a pdf") }
        val out = File(context.cacheDir, "edit_bad_out.pdf")
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        val result = editor.replaceText(
            context = ctx,
            inputUri = Uri.fromFile(bad),
            outputStream = FileOutputStream(out),
            pageIndex = 0,
            oldText = "x",
            newText = "y"
        )

        assertTrue(result.isFailure)
    }
}
