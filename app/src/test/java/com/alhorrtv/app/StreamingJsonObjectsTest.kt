package com.alhorrtv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class StreamingJsonObjectsTest {
    @Test
    fun parsesLargeArrayIncrementally() {
        val total = 50_000
        var seen = 0
        var largestObject = 0
        StreamingJsonObjects.forEach(LargeJsonInputStream(total)) { raw ->
            seen++
            largestObject = maxOf(largestObject, raw.length)
            assertTrue(raw.startsWith("{"))
            assertTrue(raw.endsWith("}"))
        }
        assertEquals(total, seen)
        assertTrue("parser must retain only one bounded object", largestObject < 2048)
    }

    private class LargeJsonInputStream(private val count: Int) : InputStream() {
        private var index = 0
        private var current = ByteArray(0)
        private var position = 0
        private var finished = false
        override fun read(): Int {
            while (position >= current.size) {
                if (index < count) {
                    val prefix = if (index == 0) "[" else ","
                    current = (prefix + "{\"stream_id\":\"$index\",\"name\":\"فيلم $index\",\"category_name\":\"stress\"}").toByteArray()
                    index++; position = 0
                } else if (!finished) {
                    current = "]".toByteArray(); position = 0; finished = true
                } else return -1
            }
            return current[position++].toInt() and 0xff
        }
    }
}
