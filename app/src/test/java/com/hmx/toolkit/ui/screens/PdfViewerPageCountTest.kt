package com.hmx.toolkit.ui.screens

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Behavioral tests for the viewer page-count pipeline:
 * single source of truth (Loaded.totalPages from PDDocument.numberOfPages),
 * document switches, reloads, malformed input, and navigation boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfViewerPageCountTest {

    private lateinit var context: Context
    private lateinit var viewModel: PdfViewerViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        viewModel = PdfViewerViewModel()
    }

    private fun createPdf(pages: Int, name: String): File {
        val file = File(context.cacheDir, name)
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            doc.save(file)
        }
        return file
    }

    private suspend fun loadAndAwaitLoaded(uri: Uri): PdfViewerUiState.Loaded {
        viewModel.loadPdf(context, uri)
        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected Loaded, got $state", state is PdfViewerUiState.Loaded)
        return state as PdfViewerUiState.Loaded
    }

    @Test
    fun `single page pdf reports count of one`() = runBlocking {
        val loaded = loadAndAwaitLoaded(Uri.fromFile(createPdf(1, "one_page.pdf")))
        assertEquals(1, loaded.totalPages)
    }

    @Test
    fun `multi page pdf reports exact count`() = runBlocking {
        val loaded = loadAndAwaitLoaded(Uri.fromFile(createPdf(7, "seven_pages.pdf")))
        assertEquals(7, loaded.totalPages)
    }

    @Test
    fun `reopening another pdf updates count and generation`() = runBlocking {
        val firstGen = viewModel.documentGeneration.value
        val loaded1 = loadAndAwaitLoaded(Uri.fromFile(createPdf(5, "five_pages.pdf")))
        assertEquals(5, loaded1.totalPages)
        assertEquals(firstGen + 1, viewModel.documentGeneration.value)

        val loaded2 = loadAndAwaitLoaded(Uri.fromFile(createPdf(3, "three_pages.pdf")))
        assertEquals(3, loaded2.totalPages)
        assertEquals(firstGen + 2, viewModel.documentGeneration.value)
    }

    @Test
    fun `malformed input reports error not a page count`() = runBlocking {
        val bad = File(context.cacheDir, "not_a_pdf.pdf").apply { writeText("garbage") }
        viewModel.loadPdf(context, Uri.fromFile(bad))
        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected Error, got $state", state is PdfViewerUiState.Error)
    }

    @Test
    fun `loadPage rejects out-of-range indices without document`() = runBlocking {
        assertEquals(null, viewModel.loadPage(-1))
        assertEquals(null, viewModel.loadPage(0))
        assertEquals(null, viewModel.loadPage(99))
    }

    @Test
    fun `loadPage rejects out-of-range indices with loaded document`() = runBlocking {
        val loaded = loadAndAwaitLoaded(Uri.fromFile(createPdf(3, "bounds.pdf")))
        assertEquals(3, loaded.totalPages)
        assertEquals(null, viewModel.loadPage(-1))
        assertEquals(null, viewModel.loadPage(3))
        assertEquals(null, viewModel.loadPage(100))
    }
}
