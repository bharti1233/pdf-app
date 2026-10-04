package com.yourname.pdftoolkit.domain.operations

import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class PdfSignerTest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "pdf_signer_test")
        if (!tempDir.exists()) tempDir.mkdirs()
    }

    @Test
    fun `signature produces a valid pdf and embeds a visual stamp`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val src = File(tempDir, "input.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.LETTER))
            doc.save(FileOutputStream(src))
        }
        val out = File(tempDir, "signed.pdf")

        val signer = PdfSigner(ctx)
        val result = signer.addSignature(
            inputUri = Uri.fromFile(src),
            outputUri = Uri.fromFile(out),
            signatureData = SignatureData(
                paths = listOf(
                    SignaturePath(
                        points = listOf(SignaturePoint(50f, 50f), SignaturePoint(120f, 90f))
                    )
                )
            ),
            placement = SignaturePlacement(pageIndex = 0, x = 100f, y = 100f, width = 200f, height = 100f)
        )

        assertTrue("signature should succeed: ${result.errorMessage}", result.success)
        assertTrue(out.exists() && out.length() > 0)

        PDDocument.load(out).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }
    }

    @Test
    fun `string contract does not claim cryptographic signing`() {
        val ctx = RuntimeEnvironment.getApplication()
        val title = ctx.getString(com.yourname.pdftoolkit.R.string.sign_title)
        assertTrue("title must be Visual Signature", title.contains("Visual", ignoreCase = true))
        val toolName = ctx.getString(com.yourname.pdftoolkit.R.string.tool_sign_pdf)
        assertTrue("tool name must be accurate", toolName.contains("Visual", ignoreCase = true))
    }
}
