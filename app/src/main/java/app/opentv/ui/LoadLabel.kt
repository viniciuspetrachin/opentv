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

/** `42%`, or null when the size of the job is not known yet. */
@Composable
fun loadPercent(snapshot: LoadSnapshot): String? =
    snapshot.fraction?.let { "${(it * 100).toInt()}%" }

/** `400 / 900 canais`, or just the running count when the total is not known yet. */
@Composable
fun loadStepLabel(snapshot: LoadSnapshot): String {
    val format = NumberFormat.getIntegerInstance()
    val done = format.format(snapshot.done)
    val total = snapshot.total?.let { format.format(it) }
    val withTotal = total != null
    val res = when (snapshot.step) {
        LoadStep.CHANNELS -> if (withTotal) R.string.load_step_channels else R.string.load_step_channels_so_far
        LoadStep.MOVIES -> if (withTotal) R.string.load_step_movies else R.string.load_step_movies_so_far
        LoadStep.SERIES -> if (withTotal) R.string.load_step_series else R.string.load_step_series_so_far
        LoadStep.GUIDE -> if (withTotal) R.string.load_step_guide else R.string.load_step_guide_so_far
        LoadStep.MATCH -> if (withTotal) R.string.load_step_match else R.string.load_step_match_so_far
    }
    return if (total == null) stringResource(res, done) else stringResource(res, done, total)
}
