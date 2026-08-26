package com.royalshuffle.android.domain.shuffle

import java.security.SecureRandom

class TrueRandomShuffle(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun <T> shuffle(items: List<T>): List<T> {
        val shuffled = items.toMutableList()

        for (index in shuffled.lastIndex downTo 1) {
            val swapIndex = secureRandom.nextInt(index + 1)
            val item = shuffled[index]
            shuffled[index] = shuffled[swapIndex]
            shuffled[swapIndex] = item
        }

        return shuffled
    }
}
