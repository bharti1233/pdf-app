package com.hmx.toolkit

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies flavor/feature gating is internally consistent:
 * URL → PDF and ML Kit OCR must only be enabled for the playstore variant;
 * HAS_OCR must be true for every variant.
 */
class FlavorGatingTest {

    @Test
    fun `url-to-pdf flag matches playstore flavor only`() {
        val isPlaystore = BuildConfig.FLAVOR == "playstore"
        assertTrue(
            "HAS_NETWORK_URL_TO_PDF must equal (flavor == playstore), got ${BuildConfig.HAS_NETWORK_URL_TO_PDF} for ${BuildConfig.FLAVOR}",
            BuildConfig.HAS_NETWORK_URL_TO_PDF == isPlaystore
        )
    }

    @Test
    fun `mlkit ocr flag matches playstore flavor only`() {
        val isPlaystore = BuildConfig.FLAVOR == "playstore"
        assertTrue(BuildConfig.USE_MLKIT_OCR == isPlaystore)
    }

    @Test
    fun `ocr is enabled in all flavors`() {
        assertTrue(BuildConfig.HAS_OCR)
    }
}
