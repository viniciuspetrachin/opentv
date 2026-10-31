/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * Open-addressed set of primitive longs.
 *
 * A [HashSet] of boxed [Long]s costs an object per id. A few hundred thousand channel ids then
 * sit in the heap for the whole import. This keeps the ids in one array: 400,000 entries are a
 * few megabytes, and a collision is caught later by the unique index on the channel row.
 */
internal class LongOpenSet(expected: Int = 16) {
    private var capacity = nextPowerOfTwo((expected * 2).coerceAtLeast(16))
    private var keys = LongArray(capacity)
    private var used = BooleanArray(capacity)
    private var count = 0

    /** @return true when [value] was not already present. */
    fun add(value: Long): Boolean {
        if (count * 2 >= capacity) grow()
        var slot = mix(value) and (capacity - 1)
        while (used[slot]) {
            if (keys[slot] == value) return false
            slot = (slot + 1) and (capacity - 1)
        }
        used[slot] = true
        keys[slot] = value
        count++
        return true
    }

    private fun grow() {
        val oldKeys = keys
        val oldUsed = used
        capacity *= 2
        keys = LongArray(capacity)
        used = BooleanArray(capacity)
        count = 0
        for (i in oldKeys.indices) {
            if (oldUsed[i]) add(oldKeys[i])
        }
    }

    private fun mix(value: Long): Int {
        var x = value
        x = x xor (x ushr 32)
        return (x.toInt() * 0x9E3779B9.toInt())
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var n = value - 1
        n = n or (n ushr 1)
        n = n or (n ushr 2)
        n = n or (n ushr 4)
        n = n or (n ushr 8)
        n = n or (n ushr 16)
        return if (n < 16) 16 else n + 1
    }
}
