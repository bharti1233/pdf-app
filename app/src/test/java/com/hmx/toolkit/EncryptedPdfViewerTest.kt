package com.hmx.toolkit

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.hmx.toolkit.ui.screens.PdfViewerUiState
import com.hmx.toolkit.ui.screens.PdfViewerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedPdfViewerTest {

    private lateinit var testDir: File
    private lateinit var simpleEncrypted: File
    private lateinit var unicodeEncrypted: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        // Generate real encrypted fixtures at test time (no checked-in binaries).
        testDir = File(context.cacheDir, "encrypted_fixtures").apply { mkdirs() }
        simpleEncrypted = File(testDir, "encrypted_simple.pdf")
        unicodeEncrypted = File(testDir, "encrypted_unicode_pwd.pdf")
        createEncryptedPdf(simpleEncrypted, "secret123")
        createEncryptedPdf(unicodeEncrypted, "pässwörd123")
    }

    private fun createEncryptedPdf(file: File, password: String) {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.protect(StandardProtectionPolicy(password, password, AccessPermission()))
            doc.save(file)
        }
    }

    @Test
    fun encryptedPdf_withoutPassword_setsPasswordRequiredNotIncorrect(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(simpleEncrypted)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected PasswordRequired, got $state", state is PdfViewerUiState.PasswordRequired)
        val pwdState = state as PdfViewerUiState.PasswordRequired
        assertEquals(false, pwdState.isIncorrect)
    }

    @Test
    fun encryptedPdf_withWrongPassword_setsPasswordRequiredIncorrect(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(simpleEncrypted)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "wrong_password")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected PasswordRequired, got $state", state is PdfViewerUiState.PasswordRequired)
        val pwdState = state as PdfViewerUiState.PasswordRequired
        assertEquals(true, pwdState.isIncorrect)
    }

    @Test
    fun encryptedPdf_withCorrectPassword_loadsSuccessfully(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(simpleEncrypted)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "secret123")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected Loaded, got $state", state is PdfViewerUiState.Loaded)
        val loadedState = state as PdfViewerUiState.Loaded
        assertTrue("Expected at least 1 page", loadedState.totalPages >= 1)
    }

    @Test
    fun encryptedPdf_withUnicodePassword_loadsSuccessfully(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(unicodeEncrypted)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "pässwörd123")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected Loaded, got $state", state is PdfViewerUiState.Loaded)
        val loadedState = state as PdfViewerUiState.Loaded
        assertTrue("Expected at least 1 page", loadedState.totalPages >= 1)
    }
}
