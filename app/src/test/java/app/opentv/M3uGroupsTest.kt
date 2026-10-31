/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.StreamKind
import app.opentv.data.parser.M3uGroups
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uGroupsTest {

    @Test
    fun `film prefixes are movies`() {
        assertThat(M3uGroups.kindOf("FILMES | DRAMA")).isEqualTo(StreamKind.MOVIE)
        assertThat(M3uGroups.kindOf("FILME |")).isEqualTo(StreamKind.MOVIE)
        assertThat(M3uGroups.kindOf("  filme")).isEqualTo(StreamKind.MOVIE)
    }

    @Test
    fun `series prefixes are shows`() {
        assertThat(M3uGroups.kindOf("SERIES | NOVELAS")).isEqualTo(StreamKind.SERIES)
        assertThat(M3uGroups.kindOf("SÉRIES |")).isEqualTo(StreamKind.SERIES)
        assertThat(M3uGroups.kindOf("SERIE")).isEqualTo(StreamKind.SERIES)
    }

    @Test
    fun `television groups stay live`() {
        assertThat(M3uGroups.kindOf("Canais Globo")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uGroups.kindOf("Novelas")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uGroups.kindOf("HBO")).isEqualTo(StreamKind.LIVE)
    }

    @Test
    fun `24 hour channels and mixed bouquets stay on live television`() {
        assertThat(M3uGroups.kindOf("Cine 24H")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uGroups.kindOf("Filmes e Séries 24H")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uGroups.kindOf("Séries Tokusatsus 24H")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uGroups.kindOf("Seriados 24H")).isEqualTo(StreamKind.LIVE)
        assertThat(M3uGroups.kindOf("Filmes e Séries")).isEqualTo(StreamKind.LIVE)
    }

    @Test
    fun `a leading diamond is not a category`() {
        assertThat(M3uGroups.kindOf("♦️ Novelas")).isNull()
        assertThat(M3uGroups.kindOf("♦️")).isNull()
        assertThat(M3uGroups.kindOf("◆ Sem par")).isNull()
        assertThat(M3uGroups.isDecoratedCopy("  ♦️ Novelas")).isTrue()
    }
}
