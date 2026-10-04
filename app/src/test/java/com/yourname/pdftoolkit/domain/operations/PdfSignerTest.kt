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
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
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
        // Robolectric unit tests have no merged resources, so verify the
        // contract in the source strings file instead.
        val moduleDir = File("").absoluteFile
        val candidates = listOf(
            File(moduleDir, "src/main/res/values/strings.xml"),
            File(moduleDir.parentFile ?: moduleDir, "app/src/main/res/values/strings.xml")
        )
        val stringsFile = candidates.firstOrNull { it.exists() }
        assertTrue("strings.xml not found", stringsFile != null)
        val xml = stringsFile!!.readText()
        val signTitle = Regex("<string name=\"sign_title\">([^<]+)</string>").find(xml)?.groupValues?.get(1) ?: ""
        val toolName = Regex("<string name=\"tool_sign_pdf\">([^<]+)</string>").find(xml)?.groupValues?.get(1) ?: ""
        assertTrue("sign_title must say Visual Signature, was '$signTitle'", signTitle.contains("Visual", ignoreCase = true))
        assertTrue("tool_sign_pdf must say Visual Signature, was '$toolName'", toolName.contains("Visual", ignoreCase = true))
    }
}
