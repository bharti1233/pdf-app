package com.hmx.toolkit.domain.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OcrConfidenceTest {

    @Test
    fun `page confidence is mean of word confidences`() {
        val words = listOf(
            OcrWord("hello", null, 0.5f),
            OcrWord("world", null, 1.0f)
        )
        assertEquals(0.75f, PdfOcrProcessor.averageWordConfidence(words), 0.0001f)
    }

    @Test
    fun `confidence is not the fabricated constant 0_85`() {
        val noConfidenceWords = listOf(OcrWord("hello", null, 0f))
        assertNotEquals(0.85f, PdfOcrProcessor.averageWordConfidence(noConfidenceWords), 0.0001f)
    }

    @Test
    fun `unavailable confidence is zero not a placeholder`() {
        assertEquals(0f, PdfOcrProcessor.averageWordConfidence(emptyList()), 0.0001f)
        val mlKitStub = listOf(OcrWord("hello", null, 0f))
        assertEquals(0f, PdfOcrProcessor.averageWordConfidence(mlKitStub), 0.0001f)
    }
}
