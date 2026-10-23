package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun formatMillis_formatsCorrectly() {
        assertEquals("00:10", formatMillis(10_000L))
        assertEquals("01:05", formatMillis(65_000L))
        assertEquals("01:00:00", formatMillis(3_600_000L))
    }
}
