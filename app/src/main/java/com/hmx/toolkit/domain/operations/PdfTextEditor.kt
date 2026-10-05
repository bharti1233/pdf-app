package com.hmx.toolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Result of a true text-replacement operation.
 */
data class TextEditResult(
    val pageIndex: Int,
    val charsReplaced: Int
)

/**
 * Replaces existing PDF text with new text (true content removal, not an overlay).
 *
 * How it works:
 * 1. Extracts the page's glyph sequence in content-stream order.
 * 2. Locates [oldText] in that sequence.
 * 3. Rewrites the page content stream, replacing the matched glyph bytes with
 *    spaces (0x20) — the original bytes are gone from the saved document, so
 *    text extraction / search / copy-paste can no longer recover them.
 * 4. Draws [newText] at the recorded position with the recorded font size.
 *
 * Honest limitations (failures are explicit, never silent):
 * - Only single-byte-encoded text is supported. Pages whose matched range
 *   uses a multi-byte CID font (PDType0Font) are rejected.
 * - Matching is done in content-stream order; for multi-column layouts the
 *   selected text may not be found — the operation then fails with
 *   "text not found" instead of editing the wrong text.
 * - Replacement style is approximated (Helvetica, recorded size, black).
 *   Shorter-or-equal replacements fit cleanly; longer text may overprint
 *   following content.
 */
class PdfTextEditor {

    suspend fun replaceText(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        pageIndex: Int,
        oldText: String,
        newText: String,
        onProgress: (Float) -> Unit = {}
    ): Result<TextEditResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        try {
            ensureActive()
            onProgress(0.05f)

            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())

            val result = replaceInDocument(document, pageIndex, oldText, newText) { p ->
                onProgress(0.05f + p * 0.8f)
            }
            val edit = result.getOrElse { e ->
                return@withContext Result.failure(e)
            }

            document.save(outputStream)
            outputStream.flush()
            onProgress(1.0f)

            Result.success(edit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) { }
        }
    }

    /**
     * Replaces text inside an already-open [PDDocument] (in-memory editing for
     * the viewer: no temp files, no reload round-trip).
     */
    fun replaceInDocument(
        document: PDDocument,
        pageIndex: Int,
        oldText: String,
        newText: String,
        onProgress: (Float) -> Unit = {}
    ): Result<TextEditResult> {
        return try {
            onProgress(0.05f)

            if (oldText.isBlank()) {
                return Result.failure(
                    IllegalArgumentException("No text selected to replace")
                )
            }
            if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
                return Result.failure(
                    IllegalArgumentException("Invalid page index $pageIndex")
                )
            }
            onProgress(0.15f)

            // 1-2. Glyph sequence in content-stream order (same order the
            // content-stream tokens below are walked in).
            val positions = extractPositions(document, pageIndex)
                ?: return Result.failure(
                    IllegalStateException("No extractable text on page ${pageIndex + 1}")
                )
            val raw = positions.joinToString("") { it.unicode }
            val matchStart = raw.indexOf(oldText)
            if (matchStart < 0) {
                return Result.failure(
                    IllegalStateException(
                        "Selected text was not found in page order. " +
                            "Try selecting within a single line."
                    )
                )
            }
            val matchEnd = matchStart + oldText.length
            onProgress(0.3f)

            // 3. Capability gate: single-char glyphs, no multi-byte CID fonts.
            for (i in matchStart until matchEnd) {
                val pos = positions[i]
                if (pos.unicode.length != 1) {
                    return Result.failure(
                        UnsupportedOperationException(
                            "Text uses complex glyphs that cannot be safely replaced"
                        )
                    )
                }
                if (pos.font is PDType0Font) {
                    return Result.failure(
                        UnsupportedOperationException(
                            "Text uses an embedded CID font that cannot be safely replaced"
                        )
                    )
                }
            }

            // 4. Blank the matched bytes inside Tj/TJ/'/" string operands.
            val page = document.getPage(pageIndex)
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens
            var glyphCursor = 0
            var blanked = 0
            var ti = 0
            while (ti < tokens.size) {
                val token = tokens[ti]
                if (token is Operator && token.name in TEXT_SHOWING_OPS) {
                    val operand = if (ti > 0) tokens[ti - 1] else null
                    when (token.name) {
                        "Tj", "'", "\"" -> {
                            if (operand is COSString) {
                                val bytes = operand.bytes
                                for (bi in bytes.indices) {
                                    if (glyphCursor in matchStart until matchEnd) {
                                        bytes[bi] = 0x20
                                        blanked++
                                    }
                                    glyphCursor++
                                }
                                operand.value = bytes
                            }
                        }
                        "TJ" -> {
                            if (operand is COSArray) {
                                for (ai in 0 until operand.size()) {
                                    val element = operand.getObject(ai)
                                    if (element is COSString) {
                                        val bytes = element.bytes
                                        for (bi in bytes.indices) {
                                            if (glyphCursor in matchStart until matchEnd) {
                                                bytes[bi] = 0x20
                                                blanked++
                                            }
                                            glyphCursor++
                                        }
                                        element.value = bytes
                                    }
                                }
                            }
                        }
                    }
                }
                ti++
            }

            if (glyphCursor != positions.size) {
                return Result.failure(
                    IllegalStateException(
                        "Page text cannot be mapped to editable content " +
                            "(form objects or mixed encodings are not supported)"
                    )
                )
            }
            if (blanked != oldText.length) {
                return Result.failure(
                    IllegalStateException("Could not locate the selected text in the page content")
                )
            }
            onProgress(0.6f)

            // 5. Write the sanitized content stream back.
            val sanitized = PDStream(document)
            sanitized.createOutputStream().use { out ->
                ContentStreamWriter(out).writeTokens(tokens)
            }
            page.cosObject.setItem(COSName.CONTENTS, sanitized.cosObject)
            onProgress(0.75f)

            // 6. Draw the replacement at the recorded position/size.
            if (newText.isNotBlank()) {
                val first = positions[matchStart]
                val avgSize = positions.subList(matchStart, matchEnd)
                    .map { it.fontSizeInPt }
                    .average().toFloat().coerceIn(6f, 72f)
                PDPageContentStream(
                    document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    cs.setFont(PDType1Font.HELVETICA, avgSize)
                    cs.beginText()
                    cs.newLineAtOffset(first.xDirAdj, first.yDirAdj)
                    cs.showText(newText)
                    cs.endText()
                }
            }
            onProgress(0.9f)

            Result.success(TextEditResult(pageIndex, blanked))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
        // NOTE: the passed-in document stays open (caller-owned).
    }

    private fun extractPositions(document: PDDocument, pageIndex: Int): List<TextPosition>? {
        return try {
            val positions = mutableListOf<TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    super.processTextPosition(text)
                    positions.add(text)
                }
            }
            // Stream order (NOT sorted): must match the token walk above.
            stripper.sortByPosition = false
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(document)
            positions.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val TEXT_SHOWING_OPS = setOf("Tj", "TJ", "'", "\"")
    }
}
