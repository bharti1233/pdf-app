package com.hmx.toolkit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RatingManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RatingManager.reset(context)
    }

    @Test
    fun testIncrementUsage() = runBlocking {
        // 1st usage
        assertFalse(RatingManager.incrementUsage(context))

        // 2nd usage
        assertFalse(RatingManager.incrementUsage(context))

        // 3rd usage
        assertFalse(RatingManager.incrementUsage(context))

        // 4th usage - should return true (threshold contract)
        val result = RatingManager.incrementUsage(context)
        assertTrue("Should return true on 4th usage", result)
    }

    @Test
    fun testNoRepeat() = runBlocking {
        // Simulate 4 usages
        repeat(4) { RatingManager.incrementUsage(context) }

        // 5th usage
        val result = RatingManager.incrementUsage(context)
        assertFalse("Should not trigger again", result)
    }
}
