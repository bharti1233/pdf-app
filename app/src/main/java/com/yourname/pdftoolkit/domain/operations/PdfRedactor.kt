package com.yourname.pdftoolkit.domain.operations

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Represents an area to redact on a specific page.
 */
data class RedactionArea(
    val pageIndex: Int, // 0-indexed
    val rect: RectF // In PDF coordinates (origin at bottom-left)
)

/**
 * Result of a redaction operation.
 */
data class RedactionResult(
    val areasRedacted: Int,
    val pagesAffected: Int
)

/**
 * Redaction color options.
 */
enum class RedactionColor(val r: Float, val g: Float, val b: Float) {
    BLACK(0f, 0f, 0f),
    WHITE(1f, 1f, 1f),
    GRAY(0.5f, 0.5f, 0.5f)
}

/**
 * Handles PDF redaction operations.
 *
 * This is a SECURITY redaction: every affected page is re-rendered to a
 * bitmap, the redaction boxes are painted into that bitmap, and the page
 * is replaced by the sanitized image. The original page content (text,
 * vectors, images, annotations) is discarded for affected pages, so the
 * underlying content can no longer be extracted or recovered by removing
 * an overlay. Unaffected pages are preserved as-is.
 *
 * Trade-off: redacted pages are no longer text-searchable/selectable.
 * This is deliberate and documented; a "redact but keep text" mode would
 * not be safe.
 */
class PdfRedactor {

    /** DPI used when rasterizing redacted pages. */
    private val redactionDpi = 200

    /**
     * Redact specified areas in a PDF.
     *
     * @param context Android context
     * @param inputUri URI of the PDF to redact
     * @param outputStream Output stream for the redacted PDF
     * @param areas List of areas to redact
     * @param color Color of redaction boxes
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return RedactionResult with operation details
     */
    suspend fun redactAreas(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        areas: List<RedactionArea>,
        color: RedactionColor = RedactionColor.BLACK,
        onProgress: (Float) -> Unit = {}
    ): Result<RedactionResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var newDocument: PDDocument? = null

        try {
            onProgress(0.1f)

            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )

            document = PDDocument.load(inputStream)
            val totalPages = document.numberOfPages

            if (totalPages == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }

            // Validate areas
            val invalidAreas = areas.filter { it.pageIndex < 0 || it.pageIndex >= totalPages }
            if (invalidAreas.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid page indices: ${invalidAreas.map { it.pageIndex }}")
                )
            }

            onProgress(0.2f)

            val areasByPage = areas.groupBy { it.pageIndex }
            val pagesAffected = areasByPage.keys.size
            var areasRedacted = 0

            newDocument = PDDocument()
            val renderer = PDFRenderer(document)

            for (pageIndex in 0 until totalPages) {
                val page = document.getPage(pageIndex)
                val pageAreas = areasByPage[pageIndex]

                if (pageAreas.isNullOrEmpty()) {
                    // Unaffected page: import as-is (vectors/text preserved).
                    newDocument.importPage(page)
                } else {
                    // Affected page: rasterize, paint secure boxes, replace content.
                    val bitmap = renderer.renderImageWithDPI(pageIndex, redactionDpi.toFloat(), ImageType.RGB)
                    try {
                        val scale = redactionDpi / 72f
                        val paint = Paint().apply {
                            isAntiAlias = false
                            setColor(android.graphics.Color.rgb(
                                (color.r * 255).toInt(),
                                (color.g * 255).toInt(),
                                (color.b * 255).toInt()
                            ))
                        }
                        if (bitmap.isRecycled) throw IllegalStateException("Page bitmap recycled before redaction")
                        val canvas = android.graphics.Canvas(bitmap) // isRecycled guarded above
                        val pageHeight = page.mediaBox.height
                        for (area in pageAreas) {
                            val left = area.rect.left * scale
                            val right = area.rect.right * scale
                            // PDF origin is bottom-left; bitmap origin is top-left.
                            val top = (pageHeight - area.rect.top) * scale
                            val bottom = (pageHeight - area.rect.bottom) * scale
                            canvas.drawRect(left, top, right, bottom, paint)
                            areasRedacted++
                        }

                        val newPage = PDPage(PDRectangle(page.mediaBox.width, page.mediaBox.height))
                        newDocument.addPage(newPage)
                        val xImage = LosslessFactory.createFromImage(newDocument, bitmap)
                        PDPageContentStream(newDocument, newPage).use { cs ->
                            cs.drawImage(xImage, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }

                onProgress(0.2f + 0.7f * (pageIndex + 1).toFloat() / totalPages)
            }

            onProgress(0.95f)

            newDocument.save(outputStream)
            outputStream.flush()

            onProgress(1.0f)

            Result.success(
                RedactionResult(
                    areasRedacted = areasRedacted,
                    pagesAffected = pagesAffected
                )
            )

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { newDocument?.close() } catch (_: Exception) {}
            document?.close()
        }
    }

    /**
     * Create a redaction area from normalized coordinates (0.0 to 1.0).
     * This makes it easier to specify areas regardless of page size.
     *
     * @param pageIndex Page index (0-indexed)
     * @param left Left position (0.0 = left edge, 1.0 = right edge)
     * @param top Top position (0.0 = top edge, 1.0 = bottom edge)
     * @param right Right position
     * @param bottom Bottom position
     * @param pageWidth Actual page width in points
     * @param pageHeight Actual page height in points
     */
    fun createRedactionArea(
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        pageWidth: Float,
        pageHeight: Float
    ): RedactionArea {
        // Convert from normalized coordinates to PDF coordinates
        // PDF origin is at bottom-left, so we need to flip Y
        val pdfLeft = left * pageWidth
        val pdfRight = right * pageWidth
        val pdfTop = (1f - top) * pageHeight
        val pdfBottom = (1f - bottom) * pageHeight

        return RedactionArea(
            pageIndex = pageIndex,
            rect = RectF(pdfLeft, pdfBottom, pdfRight, pdfTop)
        )
    }

    /**
     * Text-based redaction is NOT supported. It always fails explicitly so
     * callers cannot accidentally believe text was securely removed. Use
     * area-based [redactAreas] instead.
     */
    suspend fun redactText(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        textToRedact: String,
        color: RedactionColor = RedactionColor.BLACK,
        onProgress: (Float) -> Unit = {}
    ): Result<RedactionResult> = withContext(Dispatchers.IO) {
        Result.failure(
            UnsupportedOperationException(
                "Text-based redaction is not supported. Use area-based redaction (redactAreas) instead."
            )
        )
    }

    /**
     * Get page dimensions for helping users specify redaction areas.
     */
    suspend fun getPageDimensions(
        context: Context,
        uri: Uri,
        pageIndex: Int
    ): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    if (pageIndex in 0 until document.numberOfPages) {
                        val page = document.getPage(pageIndex)
                        val mediaBox = page.mediaBox
                        Pair(mediaBox.width, mediaBox.height)
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
