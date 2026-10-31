/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.core.LongOpenSet
import app.opentv.data.model.Channel
import java.io.BufferedReader
import kotlinx.coroutines.runBlocking

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * Reads one line at a time and hands each accepted channel to [parse]'s callback. The caller
 * decides when to write. Nothing here keeps the playlist: a few hundred thousand entries must
 * not become a few hundred thousand objects sitting in a list until the file ends.
 *
 * Tolerant by design. A malformed entry is skipped, not fatal — one bad line in a large
 * playlist must never cost the user their entire channel list.
 */
object M3uParser {

    data class Header(
        /** Value of `url-tvg` / `x-tvg-url` on the #EXTM3U header, if the playlist declares one. */
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
        /** Distinct `group-title` values, in the order they first appeared. */
        val categories: List<String>,
    )

    /**
     * Collected result for tests and other callers that already hold a small playlist in a
     * string. The sync path does not use this — it streams straight into the database.
     */
    data class Result(
        val channels: List<Channel>,
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
    )

    fun parse(text: String, sourceId: Long): Result = runBlocking {
        val channels = ArrayList<Channel>()
        val header = parse(text.reader().buffered(), sourceId) { channels += it }
        Result(channels, header.declaredEpgUrl, header.skippedEntries)
    }

    suspend fun parse(
        reader: BufferedReader,
        sourceId: Long,
        onChannel: suspend (Channel) -> Unit,
    ): Header {
        val seen = LongOpenSet()
        val categories = LinkedHashSet<String>()
        var declaredEpgUrl: String? = null
        var skipped = 0
        var pendingName: String? = null
        var pendingTvgId: String? = null
        var pendingTvgName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingNumber: Int? = null
        var pendingXui: String? = null
        var index = 0

        while (true) {
            val rawLine = reader.readLine() ?: break
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    declaredEpgUrl = attribute(line, "url-tvg")
                        ?: attribute(line, "x-tvg-url")
                        ?: declaredEpgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingTvgId = attribute(line, "tvg-id")
                    pendingTvgName = attribute(line, "tvg-name")
                    pendingLogo = attribute(line, "tvg-logo") ?: attribute(line, "logo")
                    pendingGroup = attribute(line, "group-title")
                    pendingNumber = attribute(line, "tvg-chno")?.toIntOrNull()
                    pendingXui = attribute(line, "xui-id")
                    pendingName = displayNameOf(line, pendingTvgName)
                }

                // Any other directive: ignore, but keep the pending EXTINF alive.
                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank()) {
                        skipped++
                    } else {
                        // xui-id is one row. tvg-id is the guide id and is often shared by
                        // different affiliates, so it must not be the row identity.
                        val streamId = pendingXui
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "xui:$it" }
                            ?: "url:${java.lang.Long.toHexString(fnv64(line))}"
                        // A repeated xui-id (or the same url) keeps the first. A 64-bit hash
                        // stands in for the id so the set stays a few megabytes.
                        if (seen.add(fnv64(streamId))) {
                            val group = pendingGroup?.takeIf { it.isNotBlank() }
                            if (group != null) categories.add(group)
                            onChannel(
                                normalizedChannel(
                                    sourceId = sourceId,
                                    streamId = streamId,
                                    name = name,
                                    categoryId = group,
                                    logoUrl = pendingLogo?.takeIf { it.isNotBlank() },
                                    epgChannelId = pendingTvgId?.takeIf { it.isNotBlank() },
                                    number = pendingNumber,
                                    streamUrl = line,
                                    sortIndex = index++,
                                ),
                            )
                        }
                    }
                    pendingName = null
                    pendingTvgId = null
                    pendingTvgName = null
                    pendingLogo = null
                    pendingGroup = null
                    pendingNumber = null
                    pendingXui = null
                }
            }
        }

        return Header(declaredEpgUrl, skipped, categories.toList())
    }

    /**
     * The display name is whatever follows the last comma on the #EXTINF line. Falling back
     * to `tvg-name` matters because a fair number of playlists emit `#EXTINF:-1 ...,` with
     * nothing after the comma.
     */
    private fun displayNameOf(line: String, tvgName: String?): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: tvgName?.takeIf { it.isNotBlank() }
    }

    /**
     * One quoted attribute, matched by name. `logo` does not match the tail of `tvg-logo`:
     * the name has to start at the beginning of the line or after whitespace.
     */
    private fun attribute(line: String, key: String): String? {
        val needle = "$key=\""
        var from = 0
        while (from < line.length) {
            val start = line.indexOf(needle, from, ignoreCase = true)
            if (start < 0) return null
            val boundary = start == 0 || line[start - 1] == ' ' || line[start - 1] == '\t'
            if (boundary) {
                val valueStart = start + needle.length
                val end = line.indexOf('"', valueStart)
                if (end < 0) return null
                return line.substring(valueStart, end)
            }
            from = start + 1
        }
        return null
    }
}

/** FNV-1a 64-bit. Stable across processes, unlike [String.hashCode]. */
internal fun fnv64(value: String): Long {
    var hash = 0xcbf29ce484222325uL.toLong()
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash
}

/** Decorative list headings: starts AND ends with a run of banner characters. */
internal fun isSeparatorRow(rawName: String): Boolean {
    val trimmed = rawName.trim()
    if (trimmed.length < 6) return false
    return trimmed.take(3).all { it in SEPARATOR_MARKS } &&
        trimmed.takeLast(3).all { it in SEPARATOR_MARKS }
}

private val SEPARATOR_MARKS = charArrayOf('#', '*', '=', '~', '-', '_', '•', '█', '▓', '|')

private fun normalizedChannel(
    sourceId: Long,
    streamId: String,
    name: String,
    categoryId: String?,
    logoUrl: String?,
    epgChannelId: String?,
    number: Int?,
    streamUrl: String,
    sortIndex: Int,
): Channel {
    val normalized = ChannelNameNormalizer.normalize(name)
    var rank = normalized.qualityRank
    var label = normalized.qualityLabel
    if ((label.isEmpty() || rank == 0) && categoryId != null) {
        val fromCategory = ChannelNameNormalizer.normalize(categoryId)
        if (label.isEmpty()) label = fromCategory.qualityLabel
        if (rank == 0) rank = fromCategory.qualityRank
    }
    return Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        displayName = normalized.baseName,
        groupKey = normalized.groupKey,
        qualityRank = rank,
        qualityLabel = label,
        categoryId = categoryId,
        logoUrl = logoUrl,
        epgChannelId = epgChannelId,
        number = number,
        streamUrl = streamUrl,
        sortIndex = sortIndex,
        hidden = isSeparatorRow(name),
    )
}
