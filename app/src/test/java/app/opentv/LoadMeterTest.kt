/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.LoadMeter
import app.opentv.core.LoadStep
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoadMeterTest {

    @Test
    fun `overall percent follows the byte weight of each step`() {
        val meter = LoadMeter(minIntervalMs = 0)
        meter.plan(LoadStep.CHANNELS, 1_000)
        meter.plan(LoadStep.MOVIES, 3_000)

        meter.begin(LoadStep.CHANNELS, 1_000)
        meter.setBytes(500)
        assertThat(meter.current!!.fraction!!.toDouble()).isWithin(0.01).of(0.125)

        meter.endStep(10)
        assertThat(meter.current!!.fraction!!.toDouble()).isWithin(0.01).of(0.25)

        meter.begin(LoadStep.MOVIES, 3_000)
        meter.setBytes(1_500)
        assertThat(meter.current!!.fraction!!.toDouble()).isWithin(0.01).of(0.625)
        assertThat(meter.current!!.step).isEqualTo(LoadStep.MOVIES)
    }

    @Test
    fun `item estimate settles and then snaps to the exact count`() {
        val meter = LoadMeter(minIntervalMs = 0)
        meter.plan(LoadStep.CHANNELS, 1_000)
        meter.begin(LoadStep.CHANNELS, 1_000)

        meter.setItems(10)
        meter.setBytes(100)
        assertThat(meter.current!!.done).isEqualTo(10)
        assertThat(meter.current!!.total).isEqualTo(100)

        meter.setItems(40)
        meter.setBytes(500)
        assertThat(meter.current!!.total).isEqualTo(80)

        meter.endStep(73)
        assertThat(meter.current!!.done).isEqualTo(73)
        assertThat(meter.current!!.total).isEqualTo(73)
    }

    @Test
    fun `a step without a known size does not invent a percent`() {
        val meter = LoadMeter(minIntervalMs = 0)
        meter.begin(LoadStep.CHANNELS, -1)
        meter.setItems(12)
        meter.setBytes(400)
        assertThat(meter.current!!.fraction).isNull()
        assertThat(meter.current!!.total).isNull()
        assertThat(meter.current!!.stepFraction).isNull()
        assertThat(meter.current!!.done).isEqualTo(12)
    }

    @Test
    fun `remembered movie count is the total until the body can estimate`() {
        val meter = LoadMeter(minIntervalMs = 0)
        meter.begin(LoadStep.MOVIES, -1, rememberedItems = 2_000)
        meter.setItems(200)
        val snap = meter.current!!
        assertThat(snap.step).isEqualTo(LoadStep.MOVIES)
        assertThat(snap.done).isEqualTo(200)
        assertThat(snap.total).isEqualTo(2_000)
        assertThat(snap.stepFraction!!.toDouble()).isWithin(0.001).of(0.1)
    }

    @Test
    fun `bytes past the content length keep the remembered series count`() {
        val meter = LoadMeter(minIntervalMs = 0)
        meter.begin(LoadStep.SERIES, 100, rememberedItems = 2_000)
        meter.setItems(200)
        meter.setBytes(500)
        val snap = meter.current!!
        assertThat(snap.step).isEqualTo(LoadStep.SERIES)
        assertThat(snap.total).isEqualTo(2_000)
        assertThat(snap.stepFraction!!.toDouble()).isWithin(0.001).of(0.1)
        assertThat(meter.endStep(200)).isEqualTo(500L)
    }

    @Test
    fun `growing past a remembered count without a trustworthy length drops the total`() {
        val meter = LoadMeter(minIntervalMs = 0)
        meter.begin(LoadStep.CHANNELS, 100, rememberedItems = 50)
        meter.setItems(80)
        meter.setBytes(500)
        assertThat(meter.current!!.total).isNull()
        assertThat(meter.current!!.stepFraction).isNull()
    }
}
