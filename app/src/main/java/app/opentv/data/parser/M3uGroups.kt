/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.StreamKind
import java.util.Locale

/**
 * How an M3U `group-title` is filed.
 *
 * The channel row stays a channel. Only the category's [StreamKind] changes, so a shelf named
 * `FILMES | DRAMA` opens under Movies and `SERIES | NOVELAS` under Shows. A 24-hour channel
 * (`Cine 24H`, `Filmes e Séries 24H`, `Séries Tokusatsus 24H`) stays on the live tab, and so does
 * a bouquet named for both (`Filmes e Séries`). A leading diamond is the same shelf again under a
 * decorated name: it is not a category.
 */
object M3uGroups {
    /** A decorated copy of another shelf (`♦️ Novelas`). Not shown anywhere. */
    fun isDecoratedCopy(name: String): Boolean {
        val trimmed = name.trimStart()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        return first == '\u2666' || first == '\u25C6'
    }

    /**
     * The category kind for [name], or null when the group is a decorated copy and must not be
     * stored. Comparison is on the trimmed name, in upper case.
     */
    fun kindOf(name: String): StreamKind? {
        if (isDecoratedCopy(name)) return null
        val upper = name.trim().uppercase(Locale.ROOT)
        if (isTelevisionGroup(upper)) return StreamKind.LIVE
        return when {
            upper.startsWith("FILMES") || upper.startsWith("FILME") -> StreamKind.MOVIE
            upper.startsWith("SÉRIES") || upper.startsWith("SERIES") || upper.startsWith("SERIE") ->
                StreamKind.SERIES
            else -> StreamKind.LIVE
        }
    }

    /**
     * Live television that happens to mention films or shows in the group name.
     * `FILMES | DRAMA` is a movie shelf; `Filmes e Séries` and anything `24H` are channels.
     */
    private fun isTelevisionGroup(upper: String): Boolean {
        if (upper.contains("24H") || upper.contains("24 H") || upper.contains("24 HORAS")) return true
        val film = upper.startsWith("FILMES") || upper.startsWith("FILME")
        val show = upper.contains("SÉRIE") || upper.contains("SERIE")
        return film && show
    }
}
