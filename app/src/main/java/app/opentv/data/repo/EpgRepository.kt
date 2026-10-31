/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.core.CountingInputStream
import app.opentv.core.LoadMeter
import app.opentv.core.LoadStep
import app.opentv.core.LoadTrace
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpgChannelAliasDao
import app.opentv.data.db.EpgFeedDao
import app.opentv.data.db.ProgrammeDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.GuideSlot
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the electronic programme guide, end to end: feeds in, matched channels out.
 *
 * ## The feed model
 *
 * Guide data can come from three places at once — the provider's own XMLTV (usually thin or
 * empty), the curated free sources shipped with the app, and URLs the user adds. Each is an
 * [EpgFeed] row; every enabled feed is downloaded and merged into one guide. After a sync,
 * [EpgMatcher] joins provider channels to guide channels by normalised name, which is what
 * makes a free national guide light up channels named `UK| BBC ONE FHD`.
 *
 * ## The failure this class exists to prevent
 *
 * The standard way to refresh an EPG is: delete everything, download the new XMLTV, insert
 * it. It is simple and it is why so many IPTV players lose their guide — one stalled
 * download and the user is left with nothing, plus advice to reinstall.
 *
 * OpenTV never deletes before it has the replacement:
 *
 * - Programmes are **upserted in batches** as they stream out of the parser. The unique index
 *   on `(feedId, epgChannelId, startUtcMillis)` makes a re-run idempotent, so a sync that
 *   dies at 60% leaves 60% of a fresher guide behind — strictly better than before.
 * - Old programmes are pruned **by age**, only after at least one feed succeeds.
 * - A failed feed leaves that feed's previous data intact and records the reason on the
 *   feed row, where the settings screen shows it — no silent failure.
 */
class EpgRepository(
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    /** Guide index: title and times only. See [ProgrammeDao.observeGuideWindow]. */
    fun observeGuideWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<GuideSlot>> =
        programmeDao.observeGuideWindow(fromUtcMillis, toUtcMillis)

    /** Row count of the guide table. Debounce it: a sync writes in batches and each one emits. */
    fun observeProgrammeCount(): Flow<Int> = programmeDao.observeCount()

    /**
     * Reads the guide window in id batches of [GUIDE_ID_CHUNK]. Each batch is handed to [onChunk]
     * and then dropped — the repository never holds the whole window.
     *
     * [onChunk] runs on the caller dispatcher, after the query, so a flow can emit between batches.
     * Empty [epgChannelIds] does nothing. Duplicate ids are queried once.
     */
    suspend fun forEachGuideChunk(
        fromUtcMillis: Long,
        toUtcMillis: Long,
        epgChannelIds: Collection<String>,
        onChunk: suspend (slots: List<GuideSlot>, index: Int, count: Int) -> Unit,
    ) {
        if (epgChannelIds.isEmpty()) return
        val batches = ArrayList<List<String>>()
        val seen = HashSet<String>()
        val batch = ArrayList<String>(GUIDE_ID_CHUNK)
        for (id in epgChannelIds) {
            if (!seen.add(id)) continue
            batch.add(id)
            if (batch.size == GUIDE_ID_CHUNK) {
                batches.add(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) batches.add(batch.toList())
        val count = batches.size
        for ((index, ids) in batches.withIndex()) {
            val slots = withContext(Dispatchers.IO) {
                programmeDao.guideSlotsFor(fromUtcMillis, toUtcMillis, ids)
            }
            onChunk(slots, index, count)
        }
    }

    /** One full programme, description included, for a record or reminder dialog. */
    suspend fun programme(id: Long): Programme? = programmeDao.byId(id)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

    suspend fun upcoming(epgChannelId: String, nowUtcMillis: Long, limit: Int = 12): List<Programme> =
        programmeDao.upcoming(epgChannelId, nowUtcMillis, limit)

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return
        feedDao.insert(
            EpgFeed(name = name.trim().ifBlank { trimmed }, url = trimmed, enabled = true),
        )
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = feedDao.setEnabled(id, enabled)

    suspend fun removeFeed(feed: EpgFeed) {
        // A removed feed takes its guide data with it — orphaned programmes would otherwise
        // keep matching channels forever with content that never refreshes.
        programmeDao.deleteForFeed(feed.id)
        aliasDao.deleteForFeed(feed.id)
        feedDao.delete(feed.id)
    }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call.
     */
    suspend fun ensureFeeds() {
        for (source in sourceDao.enabled()) {
            if (feedDao.forProvider(source.id) == null) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }
        for ((name, url) in BUILT_IN_FEEDS) {
            if (feedDao.byUrl(url) == null) {
                // Off by default: shipping a switched-on 20 MB download for a country the
                // user may not live in would be rude. The EPG settings screen makes
                // enabling one a single click.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /**
     * Adds the guide downloads that will actually run to [meter], using each feed's
     * `Content-Length` or the size remembered from last time.
     */
    suspend fun plan(meter: LoadMeter, nowUtcMillis: Long, force: Boolean) {
        ensureFeeds()
        var total = 0L
        val due = ArrayList<EpgFeed>()
        for (feed in feedDao.enabled()) {
            if (!acceptsGuideDownload(feed)) continue
            if (!force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS) continue
            due += feed
            val len = probeFeed(feed)
            if (len > 0) total += len
        }
        if (due.isNotEmpty()) meter.plan(LoadStep.GUIDE, if (total > 0) total else -1L)
    }

    /** Downloads every enabled feed, merges, prunes, and re-runs the matcher. */
    suspend fun syncAll(
        nowUtcMillis: Long,
        force: Boolean = false,
        meter: LoadMeter? = null,
    ): SyncSummary = withContext(Dispatchers.IO) {
        ensureFeeds()
        maybeAutoEnableRegionalFeed()

        var succeeded = 0
        var failed = 0
        var written = 0
        val enabled = ArrayList<EpgFeed>()
        for (feed in feedDao.enabled()) {
            if (acceptsGuideDownload(feed)) enabled += feed
        }
        val due = enabled.filter { feed ->
            force || nowUtcMillis - feed.lastSyncMillis >= REFRESH_INTERVAL_MILLIS
        }
        if (due.isNotEmpty()) meter?.begin(LoadStep.GUIDE, -1L)
        var baseBytes = 0L

        for (feed in enabled) {
            if (!force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS) {
                succeeded++
                continue
            }
            var feedBytes = 0L
            when (
                val result = syncFeed(
                    feed,
                    nowUtcMillis,
                    onBytes = { read ->
                        feedBytes = read
                        meter?.setBytes(baseBytes + read)
                    },
                    onProgrammes = { count -> meter?.setItems(written + count) },
                )
            ) {
                is FeedResult.Success -> {
                    succeeded++
                    written += result.programmes
                    meter?.setItems(written)
                    feedDao.markSynced(
                        feed.id,
                        nowUtcMillis,
                        "${result.programmes} programmes, ${result.channels} channels",
                    )
                }
                is FeedResult.Failed -> {
                    failed++
                    feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason)
                    Log.w(TAG, "Feed '${feed.name}' failed: ${result.reason}")
                }
            }
            if (feedBytes > 0) settings.saveFeedBytes(feed.id, feedBytes)
            baseBytes += feedBytes
        }

        if (due.isNotEmpty()) {
            val remembered = meter?.endStep(written) ?: 0L
            if (remembered > 0 && due.size == 1) settings.saveFeedBytes(due.single().id, remembered)
        }

        if (succeeded > 0) {
            programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
        }

        val (matched, total) = runMatcher(meter)
        SyncSummary(succeeded, failed, written, matched, total)
    }

    private sealed interface FeedResult {
        data class Success(val programmes: Int, val channels: Int) : FeedResult
        data class Failed(val reason: String) : FeedResult
    }

    private suspend fun syncFeed(
        feed: EpgFeed,
        nowUtcMillis: Long,
        onBytes: (Long) -> Unit = {},
        onProgrammes: (Int) -> Unit = {},
    ): FeedResult {
        val batch = ArrayList<Programme>(BATCH_SIZE)
        val aliases = ArrayList<EpgChannelAlias>(256)
        var written = 0

        try {
            val opened = openFeedStream(feed, onBytes)
            opened.stream.use { stream ->
                val stats = XmltvParser.parse(
                    input = stream,
                    feedId = feed.id,
                    onChannelAlias = { id, displayName ->
                        aliases += EpgChannelAlias(
                            feedId = feed.id,
                            epgId = id,
                            displayName = displayName ?: id,
                            normalizedKey = ChannelNameNormalizer.normalize(displayName ?: id).groupKey,
                        )
                    },
                    onProgramme = { programme ->
                        if (programme.endUtcMillis >= nowUtcMillis - RETENTION_PAST_MILLIS) {
                            batch += programme
                            if (batch.size >= BATCH_SIZE) {
                                programmeDao.upsertAll(batch)
                                written += batch.size
                                batch.clear()
                                onProgrammes(written)
                            }
                        }
                    },
                )
                if (batch.isNotEmpty()) {
                    programmeDao.upsertAll(batch)
                    written += batch.size
                    onProgrammes(written)
                }
                if (aliases.isNotEmpty()) {
                    aliases.chunked(BATCH_SIZE).forEach { aliasDao.upsertAll(it) }
                }

                if (written == 0 && stats.programmeCount == 0) {
                    return FeedResult.Failed("Downloaded, but contained no programmes.")
                }
                return FeedResult.Success(written, stats.channelCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FeedResult.Failed(e.message ?: "Download failed.")
        }
    }

    private class OpenedFeed(val stream: InputStream)

    /**
     * Opens a feed as a decompressed XML stream. Bytes reported to [onBytes] are the HTTP body,
     * counted before gzip inflation, so they line up with `Content-Length`.
     */
    private suspend fun openFeedStream(feed: EpgFeed, onBytes: (Long) -> Unit): OpenedFeed {
        val raw: InputStream
        when {
            feed.providerSourceId != null -> {
                val source: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                raw = api.openEpgStream(source).stream
            }
            feed.url != null -> {
                val response = http.newCall(Request.Builder().url(feed.url).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("Guide download failed (HTTP ${response.code}).")
                }
                val body = response.body
                    ?: throw IllegalStateException("The server returned an empty guide.")
                raw = body.byteStream()
            }
            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }

        val counting = CountingInputStream(raw, onBytes)
        val buffered = BufferedInputStream(counting, 8 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val decoded = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
        return OpenedFeed(decoded)
    }

    /**
     * An M3U playlist with no `url-tvg` and no EPG URL the user typed has no guide. Do not
     * invent `/xmltv.php` with an empty username and password — that request is not this list.
     */
    private suspend fun acceptsGuideDownload(feed: EpgFeed): Boolean {
        val sourceId = feed.providerSourceId ?: return !feed.url.isNullOrBlank()
        val source = sourceDao.byId(sourceId) ?: return false
        if (source.kind == SourceKind.M3U && source.epgUrl.isNullOrBlank()) return false
        return true
    }

    private suspend fun probeFeed(feed: EpgFeed): Long {
        val remembered = settings.feedBytes(feed.id)
        val url = when {
            !feed.url.isNullOrBlank() -> feed.url
            feed.providerSourceId != null -> {
                val source = sourceDao.byId(feed.providerSourceId) ?: return remembered
                api.guideUrl(source).toString()
            }
            else -> return remembered
        }
        val probed = probeLength(url)
        return if (probed > 0) probed else remembered
    }

    private fun probeLength(url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            http.newCall(request).execute().use { response ->
                response.body?.close()
                if (response.code == 405 || response.code == 501) return -1L
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            -1L
        }
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * Joins every channel to the merged guide by normalised name.
     * Returns (channels with a working guide id, total channels).
     */
    suspend fun runMatcher(meter: LoadMeter? = null): Pair<Int, Int> {
        val aliases = aliasDao.all()
        val index = EpgMatcher.buildIndex(aliases.map { it.epgId to it.displayName })
        val populated = programmeDao.channelIdsWithProgrammes().toHashSet()
        val total = channelDao.countAll()
        meter?.beginCounted(LoadStep.MATCH, total)

        var afterId = 0L
        var matched = 0
        var seen = 0
        while (true) {
            val channels = channelDao.pageAfterId(afterId, MATCH_PAGE)
            if (channels.isEmpty()) break
            afterId = channels.last().id
            for (channel in channels) {
                val newMatch = index.match(channel.groupKey)
                if (newMatch != channel.matchedEpgId) {
                    channelDao.setMatchedEpgId(channel.id, newMatch)
                }
                val works = channel.epgCandidates.any { it in populated } ||
                    (newMatch != null && newMatch in populated)
                if (works) matched++
            }
            seen += channels.size
            meter?.setKnownTotal(seen, total)
        }
        meter?.endStep(seen)

        Log.i(
            TAG,
            "Matcher: $matched of $total channels have a working guide " +
                "(${aliases.size} guide aliases, ${populated.size} populated guide channels)",
        )
        return matched to total
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    /**
     * Turns on the free guide for the user's region, once, on a pristine install.
     *
     * The reasoning: a provider guide is usually empty, and "go and find Guide settings"
     * is a hurdle most people meet as a blank guide and give up on. The app already knows
     * the region — the normaliser reads it off the `UK|` prefixes the provider itself
     * ships — so when a clear majority of channels agree, enable that region's built-in.
     *
     * Guard rails: only when every built-in is untouched (never enabled, never synced)
     * and no custom feed exists. Switch it off and it stays off — a choice the user has
     * made is never overridden.
     */
    private suspend fun maybeAutoEnableRegionalFeed() {
        val all = feedDao.all()
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return
        val pristine = builtIns.all { !it.enabled && it.lastSyncMillis == 0L } &&
            all.none { !it.builtIn && it.providerSourceId == null }
        if (!pristine) return

        val regionCounts = HashMap<String, Int>()
        var afterId = 0L
        while (true) {
            val page = channelDao.pageAfterId(afterId, MATCH_PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (channel in page) {
                val region = ChannelNameNormalizer.normalize(channel.name).region ?: continue
                regionCounts.merge(region, 1, Int::plus)
            }
        }
        val top = regionCounts.maxByOrNull { it.value } ?: return
        if (top.value < MIN_CHANNELS_FOR_AUTO_REGION) return

        val feedName = REGION_TO_FEED[top.key] ?: return
        val feed = builtIns.firstOrNull { it.name == feedName } ?: return
        feedDao.setEnabled(feed.id, true)
        Log.i(TAG, "Auto-enabled '${feed.name}' — ${top.value} channels tagged ${top.key}")
    }

    companion object {
        private const val TAG = "EpgRepository"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        const val BATCH_SIZE = 500

        /** Channels per page when matching or scanning the table. Cursor is the row id. */
        private const val MATCH_PAGE = 500

        /**
         * EPG channel ids per [forEachGuideChunk] query. SQLite caps bound variables at 999 on
         * API 23; 400 leaves room for the two time parameters.
         */
        const val GUIDE_ID_CHUNK = 400

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /**
         * The curated built-in list. Criteria for being here: free, no key or signup,
         * openly licensed or explicitly public, and reachable as plain XMLTV(.gz).
         * All ship disabled; the user turns on the one for where they live.
         *
         * The UK Freeview entry is GPL-3.0 (same licence as OpenTV), regenerated every
         * 12 h, ~270 channels with logos — verified working before it earned this slot.
         */
        /** A region needs at least this many prefixed channels before we act on it. */
        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        /** Region tag (as providers write it) → built-in feed name to auto-enable. */
        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to "USA — epgshare01",
            "USA" to "USA — epgshare01",
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — Freeview (free-to-air)" to
                "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
            "UK — epgshare01 (Sky lineup)" to
                "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            "USA — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
            "Canada — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_CA1.xml.gz",
            "Australia — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
        )
    }
}
