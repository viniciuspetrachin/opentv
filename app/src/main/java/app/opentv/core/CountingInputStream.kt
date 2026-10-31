/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Counts bytes as they are read and reports the running total.
 *
 * Wrapped around a response body so a download can show progress from `Content-Length` without
 * buffering the body. [onCount] receives the absolute number of bytes read so far.
 */
internal class CountingInputStream(
    input: InputStream,
    private val onCount: (Long) -> Unit,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) tick(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = super.read(buffer, offset, length)
        if (n > 0) tick(n)
        return n
    }

    private fun tick(n: Int) {
        count += n
        onCount(count)
    }
}
