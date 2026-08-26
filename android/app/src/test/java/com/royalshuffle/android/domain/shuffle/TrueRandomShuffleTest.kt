package com.royalshuffle.android.domain.shuffle

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class TrueRandomShuffleTest {
    private val shuffle = TrueRandomShuffle()

    @Test
    fun `shuffle returns a new list without mutating the input`() {
        val input = listOf("one", "two", "three", "four")

        val result = shuffle.shuffle(input)

        assertNotSame(input, result)
        assertEquals(listOf("one", "two", "three", "four"), input)
    }

    @Test
    fun `shuffle preserves every item including duplicates`() {
        val input = listOf("a", "b", "a", "c", "b", "a")

        val result = shuffle.shuffle(input)

        assertEquals(input.groupingBy { it }.eachCount(), result.groupingBy { it }.eachCount())
    }

    @Test
    fun `shuffle handles empty and single-item lists`() {
        assertEquals(emptyList<String>(), shuffle.shuffle(emptyList<String>()))
        assertEquals(listOf("only"), shuffle.shuffle(listOf("only")))
    }

    @Test
    fun `shuffle uses secure random values for fisher yates swaps`() {
        val deterministicRandom = SequenceSecureRandom(1, 0, 1)
        val deterministicShuffle = TrueRandomShuffle(deterministicRandom)

        val result = deterministicShuffle.shuffle(listOf("a", "b", "c", "d"))

        assertEquals(listOf("c", "d", "a", "b"), result)
    }

    private class SequenceSecureRandom(vararg values: Int) : SecureRandom() {
        private val iterator = values.iterator()

        override fun nextInt(bound: Int): Int = iterator.next().also {
            require(it in 0 until bound)
        }
    }
}
