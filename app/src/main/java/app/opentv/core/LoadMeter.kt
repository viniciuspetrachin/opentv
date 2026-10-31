/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One stretch of a catalogue load. The label on screen is chosen from this, not from prose. */
enum class LoadStep {
    CHANNELS,
    MOVIES,
    SERIES,
    GUIDE,
    MATCH,
}

/**
 * What the loading line draws: which step, how many items, and the overall fraction of every
 * step whose size is already known.
 *
 * [total] is null until the body is far enough along to estimate it, then the exact count when
 * the step ends. [fraction] is null when no step has a known size — the bar stays a spinner
 * rather than inventing a percent.
 */
data class LoadSnapshot(
    val step: LoadStep,
    val done: Int,
    val total: Int?,
    val fraction: Float?,
)

/**
 * Turns byte counts into one percent for a load that has several downloads.
 *
 * Each step is weighed by its `Content-Length` (or the size remembered from the last sync).
 * The overall fraction is bytes finished, plus bytes read of the current body, divided by that
 * sum. Inside a step the item total is `items * length / bytesRead`, and it only moves when the
 * estimate changes by about 2% so the number does not flicker. The exact count replaces it when
 * the body ends.
 */
class LoadMeter(
    private val minIntervalMs: Long = 250L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUpdate: (LoadSnapshot) -> Unit = {},
) {
    private val lock = Any()
    private val plannedBytes = HashMap<LoadStep, ArrayDeque<Long>>()
    private var denominator = 0L
    private var finished = 0L
    private var step: LoadStep? = null
    private var length = -1L
    private var readBytes = 0L
    private var items = 0
    private var total: Int? = null
    private var exactTotal = false
    private var lastEmit = 0L

    /** Latest numbers, including updates that were not yet pushed to the UI. */
    var current: LoadSnapshot? = null
        private set

    /** Remember [bytes] as one upcoming download of [step]. Repeated calls add another download. */
    fun plan(step: LoadStep, bytes: Long) {
        synchronized(lock) {
            val queue = plannedBytes.getOrPut(step) { ArrayDeque() }
            if (bytes > 0) {
                queue.addLast(bytes)
                denominator += bytes
            } else {
                queue.addLast(-1L)
            }
            emit(force = true)
        }
    }

    /** Start the next download of [step]. [contentLength] replaces that download's planned size. */
    fun begin(step: LoadStep, contentLength: Long) {
        synchronized(lock) {
            val planned = plannedBytes[step]?.removeFirstOrNull() ?: -1L
            length = when {
                contentLength > 0 && planned > 0 && contentLength != planned -> {
                    denominator += contentLength - planned
                    contentLength
                }
                contentLength > 0 && planned <= 0 -> {
                    denominator += contentLength
                    contentLength
                }
                planned > 0 -> planned
                else -> -1L
            }
            this.step = step
            readBytes = 0L
            items = 0
            total = null
            exactTotal = false
            emit(force = true)
        }
    }

    /**
     * A step whose total is already known (the guide matcher walks `COUNT(*)` channels).
     * It does not change the byte weight of the job.
     */
    fun beginCounted(step: LoadStep, known: Int) {
        synchronized(lock) {
            this.step = step
            length = -1L
            readBytes = 0L
            items = 0
            total = known.coerceAtLeast(0)
            exactTotal = true
            emit(force = true)
        }
    }

    /** Absolute bytes read of the current body. */
    fun setBytes(read: Long) {
        synchronized(lock) {
            if (step == null) return
            readBytes = read.coerceAtLeast(0L)
            reestimate()
            emit(force = false)
        }
    }

    /** Items accepted so far in the current step. */
    fun setItems(count: Int) {
        synchronized(lock) {
            if (step == null) return
            items = count.coerceAtLeast(0)
            reestimate()
            emit(force = false)
        }
    }

    /** Replace the item fraction with a known total, as when paging a counted table. */
    fun setKnownTotal(count: Int, known: Int) {
        synchronized(lock) {
            if (step == null) return
            items = count.coerceAtLeast(0)
            total = known.coerceAtLeast(items)
            exactTotal = true
            emit(force = false)
        }
    }

    /**
     * Close the step on [exact] items. Returns the byte size to remember for the next sync:
     * the content length when we had one, otherwise however many bytes were actually read.
     */
    fun endStep(exact: Int): Long {
        synchronized(lock) {
            val active = step ?: return 0L
            val remembered = when {
                length > 0 -> length
                readBytes > 0 -> {
                    denominator += readBytes
                    readBytes
                }
                else -> 0L
            }
            if (length > 0) finished += length
            else if (readBytes > 0) finished += readBytes
            // The step's bytes are in [finished] now. Drop the in-progress read so this
            // snapshot does not count them twice.
            readBytes = 0L
            length = -1L
            items = exact.coerceAtLeast(0)
            total = items
            exactTotal = true
            val snap = LoadSnapshot(active, items, items, fraction())
            current = snap
            step = null
            emitSnapshot(snap, force = true)
            return remembered
        }
    }

    private fun reestimate() {
        if (exactTotal) return
        if (length <= 0L || readBytes <= 0L || items <= 0) return
        if (readBytes > length) return
        val estimate = ((items.toLong() * length) / readBytes).toInt().coerceAtLeast(items)
        val shown = total
        if (shown == null || shown == 0) {
            total = estimate
            return
        }
        val delta = kotlin.math.abs(estimate - shown).toDouble() / shown.toDouble()
        if (delta >= 0.02) total = estimate
    }

    private fun fraction(): Float? {
        if (denominator <= 0L) return null
        val currentBytes = if (length > 0L) readBytes.coerceAtMost(length) else 0L
        return ((finished + currentBytes).toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
    }

    private fun emit(force: Boolean) {
        val active = step ?: return
        val snap = LoadSnapshot(active, items, total, fraction())
        current = snap
        emitSnapshot(snap, force)
    }

    private fun emitSnapshot(snap: LoadSnapshot, force: Boolean) {
        val now = clock()
        if (!force && now - lastEmit < minIntervalMs) return
        lastEmit = now
        onUpdate(snap)
    }
}

/**
 * One catalogue load at a time. A second caller (opening Movies while the first sync is still
 * on series) sees the same [StatusBus] line instead of starting another download.
 */
object CatalogueLoad {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend (LoadMeter) -> T): T = mutex.withLock {
        val meter = LoadMeter(onUpdate = StatusBus::publish)
        try {
            block(meter)
        } finally {
            StatusBus.publish(null)
        }
    }

    suspend fun tryRun(block: suspend (LoadMeter) -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val meter = LoadMeter(onUpdate = StatusBus::publish)
            try {
                block(meter)
            } finally {
                StatusBus.publish(null)
            }
            return true
        } finally {
            mutex.unlock()
        }
    }
}
