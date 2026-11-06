package com.beezync.sdk

import kotlin.random.Random

class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    @Synchronized
    fun next(): Long {
        val exp = minOf(maxMs, baseMs shl minOf(attempt, 20))
        attempt++
        return minOf(maxMs, exp + random.nextLong(exp / 4 + 1))
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}
