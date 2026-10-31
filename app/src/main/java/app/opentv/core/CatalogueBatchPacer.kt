/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * How many catalogue rows to write in the next batch.
 *
 * Starts at a size that already survived weak devices, then grows when a batch was fast and the
 * heap has room, or shrinks when the batch was slow or memory is tight. There is no fixed
 * maximum: the device is the limit, and growth stops only when a write starts to drag or memory
 * gets tight. The next size is not a fixed step: 300 becomes 375, then 468, and a strong device
 * keeps climbing past 1 100 for as long as that stays cheap. After an out-of-memory failure
 * [shrink] cuts the size and holds growth for a couple of successful batches so it does not
 * climb straight back into the crash.
 */
class CatalogueBatchPacer(
    start: Int = START,
) {
    var size: Int = start.coerceAtLeast(FLOOR)
        private set

    private var holdGrows = 0

    /** Back to [START]. A new channel scan uses this; movies and series keep the size it reached. */
    fun reset() {
        size = START.coerceAtLeast(FLOOR)
        holdGrows = 0
    }

    /**
     * Record one finished write of [rows] that took [elapsedMs], given the heap and pressure
     * in [sample]. Returns the size to use next.
     */
    fun note(rows: Int, elapsedMs: Long, sample: Sample): Int {
        if (rows <= 0) return size
        val msPerRow = elapsedMs.toDouble() / rows.toDouble()
        val tight = sample.pressure != Pressure.OK || sample.heapFreeBytes < HEAP_TIGHT_BYTES
        val slow = msPerRow >= SLOW_MS_PER_ROW
        val roomy = sample.pressure == Pressure.OK &&
            sample.heapFreeBytes >= HEAP_ROOM_BYTES &&
            msPerRow <= FAST_MS_PER_ROW
        val proposed = when {
            tight || slow -> shrunk(size)
            roomy -> grown(size)
            else -> size
        }
        if (holdGrows > 0 && proposed > size) {
            holdGrows--
            return size
        }
        if (proposed < size) holdGrows = 0
        size = proposed
        return size
    }

    /**
     * Cut the size after a write ran out of memory. The next two roomy batches do not grow.
     * Returns the size to retry with.
     */
    fun shrink(): Int {
        holdGrows = 2
        size = shrunk(size)
        return size
    }

    data class Sample(
        val heapFreeBytes: Long,
        val pressure: Pressure,
    )

    enum class Pressure { OK, ELEVATED, CRITICAL }

    companion object {
        const val START = 300
        const val FLOOR = 40

        private const val FAST_MS_PER_ROW = 1.5
        private const val SLOW_MS_PER_ROW = 6.0
        private const val HEAP_ROOM_BYTES = 48L * 1024 * 1024
        private const val HEAP_TIGHT_BYTES = 16L * 1024 * 1024

        /** Bytes the process can still allocate before it hits its heap ceiling. */
        fun heapFreeBytes(): Long {
            val runtime = Runtime.getRuntime()
            val used = runtime.totalMemory() - runtime.freeMemory()
            return (runtime.maxMemory() - used).coerceAtLeast(0L)
        }

        private fun grown(current: Int): Int {
            if (current == Int.MAX_VALUE) return current
            val next = (current.toLong() * 5 / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return next.coerceAtLeast(current + 1)
        }

        private fun shrunk(current: Int): Int {
            if (current <= FLOOR) return FLOOR
            val next = (current * 3) / 5
            return next.coerceAtMost(current - 1).coerceAtLeast(FLOOR)
        }
    }
}
