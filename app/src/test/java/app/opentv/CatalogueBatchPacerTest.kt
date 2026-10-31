/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.CatalogueBatchPacer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogueBatchPacerTest {

    @Test
    fun `a fast batch on a roomy heap grows by about a quarter`() {
        val pacer = CatalogueBatchPacer()
        assertThat(pacer.size).isEqualTo(300)

        val first = pacer.note(300, elapsedMs = 30, sample = roomy())
        val second = pacer.note(first, elapsedMs = 30, sample = roomy())

        assertThat(first).isEqualTo(375)
        assertThat(second).isEqualTo(468)
        assertThat(second - first).isNotEqualTo(first - 300)
    }

    @Test
    fun `a slow batch shrinks`() {
        val pacer = CatalogueBatchPacer()
        val next = pacer.note(300, elapsedMs = 3_000, sample = roomy())
        assertThat(next).isEqualTo(180)
        assertThat(next).isLessThan(300)
    }

    @Test
    fun `memory pressure shrinks even when the batch was fast`() {
        val pacer = CatalogueBatchPacer()
        val next = pacer.note(
            300,
            elapsedMs = 30,
            sample = roomy().copy(pressure = CatalogueBatchPacer.Pressure.ELEVATED),
        )
        assertThat(next).isEqualTo(180)
    }

    @Test
    fun `a middling batch keeps its size`() {
        val pacer = CatalogueBatchPacer()
        val next = pacer.note(300, elapsedMs = 900, sample = roomy())
        assertThat(next).isEqualTo(300)
    }

    @Test
    fun `a fast device climbs past 1100`() {
        val pacer = CatalogueBatchPacer()
        repeat(12) {
            pacer.note(pacer.size, elapsedMs = 20, sample = roomy())
        }
        assertThat(pacer.size).isGreaterThan(1100)
    }

    @Test
    fun `growth does not stop at a fixed ceiling while the device stays fast`() {
        val pacer = CatalogueBatchPacer()
        repeat(20) {
            pacer.note(pacer.size, elapsedMs = 20, sample = roomy())
        }
        assertThat(pacer.size).isGreaterThan(4_000)
    }

    @Test
    fun `repeated slow batches stop at the floor`() {
        val pacer = CatalogueBatchPacer()
        repeat(20) {
            pacer.note(pacer.size.coerceAtLeast(1), elapsedMs = 10_000, sample = roomy())
        }
        assertThat(pacer.size).isEqualTo(CatalogueBatchPacer.FLOOR)
    }

    @Test
    fun `an out of memory shrink holds the next growth`() {
        val pacer = CatalogueBatchPacer()
        pacer.note(300, elapsedMs = 30, sample = roomy())
        assertThat(pacer.size).isEqualTo(375)

        assertThat(pacer.shrink()).isEqualTo(225)
        assertThat(pacer.note(225, elapsedMs = 20, sample = roomy())).isEqualTo(225)
        assertThat(pacer.note(225, elapsedMs = 20, sample = roomy())).isEqualTo(225)
        assertThat(pacer.note(225, elapsedMs = 20, sample = roomy())).isGreaterThan(225)
    }

    private fun roomy() = CatalogueBatchPacer.Sample(
        heapFreeBytes = 200L * 1024 * 1024,
        pressure = CatalogueBatchPacer.Pressure.OK,
    )
}