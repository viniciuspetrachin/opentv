/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.LoadSnapshot
import app.opentv.core.LoadStep
import java.text.NumberFormat

/** `10%` of this step when its total is known, otherwise the overall byte percent. */
@Composable
fun loadPercent(snapshot: LoadSnapshot): String? {
    val known = snapshot.total
    if (known != null && known > 0) {
        return "${(snapshot.done.toLong() * 100 / known).toInt().coerceIn(0, 100)}%"
    }
    return snapshot.fraction?.let { "${(it * 100).toInt()}%" }
}

/**
 * Fraction for the bar under the loading line. The step's own `done / total` wins, so the bar
 * matches the percent in the label and starts over when the phase changes.
 */
fun loadBarFraction(snapshot: LoadSnapshot): Float? =
    snapshot.stepFraction ?: snapshot.fraction

/** `Filmes 200/2.000 (10%)`, or just the running count when the total is not known yet. */
@Composable
fun loadStepLabel(snapshot: LoadSnapshot): String {
    val format = NumberFormat.getIntegerInstance()
    val done = format.format(snapshot.done)
    val known = snapshot.total
    val withTotal = known != null && known > 0
    val res = when (snapshot.step) {
        LoadStep.CHANNELS -> if (withTotal) R.string.load_step_channels else R.string.load_step_channels_so_far
        LoadStep.MOVIES -> if (withTotal) R.string.load_step_movies else R.string.load_step_movies_so_far
        LoadStep.SERIES -> if (withTotal) R.string.load_step_series else R.string.load_step_series_so_far
        LoadStep.GUIDE -> if (withTotal) R.string.load_step_guide else R.string.load_step_guide_so_far
        LoadStep.MATCH -> if (withTotal) R.string.load_step_match else R.string.load_step_match_so_far
    }
    if (!withTotal) return stringResource(res, done)
    val percent = (snapshot.done.toLong() * 100 / known).toInt().coerceIn(0, 100)
    return stringResource(res, done, format.format(known), percent)
}
