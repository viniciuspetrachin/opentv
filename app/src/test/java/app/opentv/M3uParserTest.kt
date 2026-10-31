/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These tests are built from the shapes real providers actually emit, including the broken
 * ones. Every "malformed" case here is something that should cost the user one channel, never
 * the whole playlist.
 */
class M3uParserTest {

    @Test
    fun `parses a well formed playlist`() {
        val playlist = """
            #EXTM3U url-tvg="http://example.com/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://img/bbc1.png" group-title="UK",BBC One HD
            http://example.com:8080/live/u/p/1.m3u8
            #EXTINF:-1 tvg-id="itv1.uk" tvg-logo="http://img/itv.png" group-title="UK",ITV 1
            http://example.com:8080/live/u/p/2.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 7)

        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrl)
            .isEqualTo("http://example.com/xmltv.php?username=u&password=p")

        val first = result.channels.first()
        assertThat(first.name).isEqualTo("BBC One HD")
        assertThat(first.epgChannelId).isEqualTo("bbc1.uk")
        assertThat(first.categoryId).isEqualTo("UK")
        assertThat(first.logoUrl).isEqualTo("http://img/bbc1.png")
        assertThat(first.sourceId).isEqualTo(7)
        assertThat(first.streamUrl).isEqualTo("http://example.com:8080/live/u/p/1.m3u8")
    }

    @Test
    fun `a URL with no EXTINF is skipped without losing the rest`() {
        val playlist = """
            #EXTM3U
            http://example.com/orphan.m3u8
            #EXTINF:-1 tvg-id="good",Good Channel
            http://example.com/good.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Good Channel")
    }

    @Test
    fun `falls back to tvg-name when the display name after the comma is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" tvg-name="Fallback Name",
            http://example.com/x.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `duplicate tvg-ids do not collide`() {
        // Affiliates often share one tvg-id. The row identity is the url (or xui-id), so both stay.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel SD
            http://example.com/sd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel HD
            http://example.com/hd.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(2)
        assertThat(result.channels.map { it.epgChannelId }).containsExactly("dup", "dup")
        assertThat(result.channels.map { it.streamId }).containsNoDuplicates()
        assertThat(result.channels.all { it.streamId.startsWith("url:") }).isTrue()
    }

    @Test
    fun `channels without a tvg-id still get a stable id derived from the url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,No Id Channel
            http://example.com/noid.m3u8
        """.trimIndent()

        val first = M3uParser.parse(playlist, sourceId = 1).channels.single()
        val second = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(first.streamId).startsWith("url:")
        // Stability matters: an unstable id means favourites are lost on every refresh.
        assertThat(first.streamId).isEqualTo(second.streamId)
        assertThat(first.epgChannelId).isNull()
    }

    @Test
    fun `unknown directives between EXTINF and the url are ignored`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTHTTP:{"User-Agent":"SomePlayer/1.0"}
            http://example.com/a.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(1)
        assertThat(channels.single().streamUrl).isEqualTo("http://example.com/a.m3u8")
    }

    @Test
    fun `channel numbers come from tvg-chno when present`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-chno="101",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().number).isEqualTo(101)
    }

    @Test
    fun `an empty playlist yields no channels rather than throwing`() {
        val result = M3uParser.parse("#EXTM3U\n", sourceId = 1)

        assertThat(result.channels).isEmpty()
        assertThat(result.skippedEntries).isEqualTo(0)
    }

    @Test
    fun `a banner row is hidden and a repeated tvg-id keeps the first`() {
        val playlist = """
            #EXTM3U url-tvg="http://example.com/guide.xml"
            #EXTINF:-1 group-title="UK",#### UK GENERAL ####
            http://example.com/banner.m3u8
            #EXTINF:-1 tvg-id="dup" group-title="UK",First
            http://example.com/first.m3u8
            #EXTINF:-1 tvg-id="dup" group-title="UK",Second
            http://example.com/second.m3u8
            http://example.com/orphan.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 3)

        assertThat(result.declaredEpgUrl).isEqualTo("http://example.com/guide.xml")
        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(3)
        assertThat(result.channels[0].hidden).isTrue()
        assertThat(result.channels[1].name).isEqualTo("First")
        assertThat(result.channels[2].name).isEqualTo("Second")
        assertThat(result.channels[1].epgChannelId).isEqualTo("dup")
        assertThat(result.channels[2].epgChannelId).isEqualTo("dup")
    }

    @Test
    fun `the same tvg-id with different xui-ids keeps both rows`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="REDEGL" xui-id="1",SP - Globo
            http://example.com/sp.m3u8
            #EXTINF:-1 tvg-id="REDEGL" xui-id="2",RJ - Globo
            http://example.com/rj.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(2)
        assertThat(channels.map { it.streamId }).containsExactly("xui:1", "xui:2").inOrder()
        assertThat(channels.map { it.epgChannelId }).containsExactly("REDEGL", "REDEGL")
    }

    @Test
    fun `a repeated xui-id keeps the first row`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 xui-id="9",First
            http://example.com/first.m3u8
            #EXTINF:-1 xui-id="9",Second
            http://example.com/second.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(1)
        assertThat(channels.single().name).isEqualTo("First")
        assertThat(channels.single().streamId).isEqualTo("xui:9")
    }
}
