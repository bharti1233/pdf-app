package com.hmx.toolkit.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.hmx.toolkit.data.FileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompressScreenTest {

    @Test
    fun testFilePickerFilter_rejectsUnresolvableUriAsPdf() {
        // CompressScreen only offers PDFs ("application/pdf" picker filter);
        // FileManager.isValidPdf is the validation gate for that flow.
        // An unresolvable content URI must not validate as a PDF.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unknownUri = Uri.parse("content://mock/unknown_file.bin")
        assertFalse(FileManager.isValidPdf(context, unknownUri))
    }

    @Test
    fun testGetFileSize_usesContentResolver() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.setResults(arrayOf(arrayOf("test_file.pdf", 2048L)))

        val uri = Uri.parse("content://mock/test_file.pdf")

        val contentResolver = context.contentResolver
        val shadowContentResolver = shadowOf(contentResolver)
        shadowContentResolver.setCursor(uri, cursor)

        val fileInfo = FileManager.getFileInfo(context, uri)

        assertEquals(2048L, fileInfo?.size)
        assertEquals("2 KB", fileInfo?.formattedSize)
    }
}
