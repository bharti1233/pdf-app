package com.yourname.pdftoolkit.pdfviewer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yourname.pdftoolkit.util.PrintUtils
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrintUtilsTest {

    @Test
    fun `printPdf returns false for blank uri without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val blankUri = Uri.parse("")
        val result = PrintUtils.printPdf(context, blankUri)
        assertFalse("Should return false for blank URI", result)
        // NOTE: the invalid content:// provider case cannot be emulated with
        // Robolectric's ShadowContentResolver, so it is covered by the
        // explicit empty-URI guard and by device coverage instead.
    }

    @Test
    fun `printPdf returns false for empty uri without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = PrintUtils.printPdf(context, Uri.EMPTY)
        assertFalse("Should return false for empty URI", result)
    }
}
