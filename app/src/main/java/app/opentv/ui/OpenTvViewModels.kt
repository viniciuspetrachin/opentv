/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.GuideSlot
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioStream
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.MovieVariantGroup
import app.opentv.data.repo.PersonTitle
import app.opentv.data.repo.distinctByQuality
import app.opentv.R
import app.opentv.pairing.ManagerServer
import app.opentv.sync.NasSync
import app.opentv.sync.SyncBundle
import app.opentv.sync.SyncClient
import app.opentv.sync.SyncPosition
import app.opentv.sync.SyncServer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.core.CatalogueLoad
import app.opentv.core.LoadStep
import app.opentv.core.LoadTrace
import app.opentv.core.StatusBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel as Signal
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * View models for the whole app.
 *
 * Grouped in one file on purpose: they are small, they share the same dependency graph, and
 * a contributor tracking down "where does the channel list come from" finds it in one place.
 */

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    data class UiState(
        val sources: List<Source> = emptyList(),
        /**
         * False until the saved sources have been read from the database once. Distinguishing
         * "no sources yet loaded" from "loaded, and there are none" is what stops a returning
         * user being sent to the setup screen — and asked for their provider again — during the
         * brief moment before the database responds on a cold launch.
         */
        val loaded: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null,
        val testError: String? = null,
        val syncing: Boolean = false,
        val syncMessage: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            graph.sourceRepository.observeAll().collect { sources ->
                _ui.value = _ui.value.copy(sources = sources, loaded = true)
            }
        }
    }

    fun test(draft: Source) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, testError = null)
            val result = graph.sourceRepository.test(draft)
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = result.getOrNull(),
                testError = result.exceptionOrNull()?.message,
            )
        }
    }

    /** Saves, then immediately pulls the catalogue so the user sees channels, not a spinner. */
    fun saveAndSync(draft: Source, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = "Saving source…")
            val id = graph.sourceRepository.save(draft)
            val saved = graph.sourceRepository.byId(id)
            if (saved == null) {
                _ui.value = _ui.value.copy(syncing = false, syncMessage = "Could not save source.")
                onDone(false)
                return@launch
            }

            _ui.value = _ui.value.copy(syncMessage = "Loading channels…")
            LoadTrace.mark("saveAndSync start id=$id kind=${saved.kind}")
            val now = System.currentTimeMillis()
            try {
                CatalogueLoad.run { meter ->
                    graph.catalogRepository.planLoad(
                        saved,
                        meter,
                        includeLive = true,
                        includeVod = saved.kind == SourceKind.XTREAM,
                    )
                    graph.epgRepository.plan(meter, now, force = false)
                    when (val result = graph.catalogRepository.syncLive(saved, now, meter)) {
                        is CatalogRepository.SyncResult.Failed -> {
                            LoadTrace.mark("saveAndSync failed id=$id ${result.reason}")
                            _ui.value = _ui.value.copy(syncing = false, syncMessage = failureText(result))
                            onDone(false)
                            return@run
                        }
                        is CatalogRepository.SyncResult.Success -> {
                            LoadTrace.mark("saveAndSync ok id=$id channels=${result.channelCount}")
                            _ui.value = _ui.value.copy(syncMessage = "Loaded ${result.channelCount} channels.")
                            onDone(true)
                            if (saved.kind == SourceKind.XTREAM) {
                                val vodOk = graph.catalogRepository.syncVod(saved, now, meter)
                                if (vodOk) graph.settings.vodSyncedAtMillis = now
                            }
                            val summary = try {
                                graph.epgRepository.syncAll(now, meter = meter)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                LoadTrace.mark("guide sync failed", t)
                                _ui.value = _ui.value.copy(syncing = false, syncMessage = failureText(t))
                                return@run
                            }
                            runCatching { graph.recordingEngine.rescanSeriesRules() }
                            _ui.value = _ui.value.copy(
                                syncing = false,
                                syncMessage = when {
                                    summary.channelsMatched > 0 ->
                                        "Guide ready — matched ${summary.channelsMatched} of " +
                                            "${summary.channelsTotal} channels."
                                    else ->
                                        "Channels ready. No guide data matched yet — add a free guide " +
                                            "under Guide settings."
                                },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(syncing = false)
                throw e
            } catch (t: Throwable) {
                LoadTrace.mark("saveAndSync fatal id=$id", t)
                _ui.value = _ui.value.copy(syncing = false, syncMessage = failureText(t))
                onDone(false)
            }
        }
    }

    private fun failureText(result: CatalogRepository.SyncResult.Failed): String = when {
        result.tooLarge -> getApplication<Application>().getString(R.string.load_failed_too_large)
        result.partial -> getApplication<Application>().getString(R.string.load_failed_partial)
        else -> result.reason
    }

    private fun failureText(error: Throwable): String =
        if (error is OutOfMemoryError) {
            getApplication<Application>().getString(R.string.load_failed_too_large)
        } else {
            error.message ?: "The catalogue could not be downloaded."
        }

    fun delete(source: Source) {
        viewModelScope.launch { graph.catalogRepository.deleteSource(source.id) }
    }

    /**
     * Change an Xtream source's live-stream container. The repository rewrites the source's live
     * channel URLs in place, so the switch takes effect without a re-sync; the observed source list
     * then re-emits with the new [Source.liveFormat]. A no-op for M3U sources and unchanged values.
     */
    fun setLiveFormat(source: Source, format: LiveStreamFormat) {
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return
        viewModelScope.launch { graph.catalogRepository.setLiveFormat(source.id, format) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            LoadTrace.mark("refreshAll start")
            _ui.value = _ui.value.copy(syncing = true, syncMessage = "Refreshing…")
            val now = System.currentTimeMillis()
            try {
                CatalogueLoad.run { meter ->
                    val sources = graph.sourceRepository.enabled()
                    val live = graph.settings.liveEnabled.value
                    for (source in sources) {
                        graph.catalogRepository.planLoad(source, meter, includeLive = live, includeVod = true)
                    }
                    graph.epgRepository.plan(meter, now, force = true)
                    var channels = 0
                    var problems = 0
                    for (source in sources) {
                        when (val result = graph.catalogRepository.sync(source, now, meter)) {
                            is CatalogRepository.SyncResult.Success -> channels += result.channelCount
                            is CatalogRepository.SyncResult.Failed -> problems++
                        }
                    }
                    val summary = graph.epgRepository.syncAll(now, force = true, meter = meter)
                    problems += summary.feedsFailed
                    runCatching { graph.recordingEngine.rescanSeriesRules() }
                    _ui.value = _ui.value.copy(
                        syncing = false,
                        syncMessage = if (problems == 0) {
                            "Refreshed $channels channels, guide matched " +
                                "${summary.channelsMatched} of ${summary.channelsTotal}."
                        } else {
                            "Refreshed $channels channels, $problems problem(s) — see Guide settings."
                        },
                    )
                }
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(syncing = false)
                throw e
            } catch (t: Throwable) {
                LoadTrace.mark("refreshAll failed", t)
                _ui.value = _ui.value.copy(syncing = false, syncMessage = failureText(t))
            }
        }
    }

    fun blankDraft() = Source(name = "", kind = SourceKind.XTREAM, url = "")
}

class ChannelsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** null = All. Exposed so the sidebar can highlight the active entry. */
    val selectedCategory = MutableStateFlow<String?>(null)
    val favouritesOnly = MutableStateFlow(false)
    /** Provider filter for the live guide. null = every source. Only surfaced when there's >1 source. */
    val selectedSource = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")
    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    /**
     * One rail entry = one *logical* category.
     *
     * Providers split categories by codec — 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc'
     * are the same shelf twice. Fold them by normalised name, show the clean label, and
     * filter across every underlying id at once. This is the on-device version of what
     * Viewella did on a server it had to pay for.
     */
    data class CategoryGroup(val key: String, val label: String, val ids: List<String>)

    val categoryGroups: StateFlow<List<CategoryGroup>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.LIVE),
            selectedSource,
        ) { raw, sourceId ->
            // Scope the category rail to the chosen provider, so a second playlist's categories show
            // on their own (cardiodoc's "keep sources separate"); null folds across every provider.
            val scoped = if (sourceId == null) raw else raw.filter { it.sourceId == sourceId }
            foldCategories(scoped)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Folds codec-split provider categories into one logical [CategoryGroup] each — 'UK| GENERAL
     * HD/RAW' and 'UK| GENERAL hevc' become a single "General" entry that filters across every
     * underlying id at once. Shared by the guide's [categoryGroups] and the manager's
     * [managerCategoryGroups] so both group the rail identically.
     */
    private fun foldCategories(raw: List<Category>): List<CategoryGroup> {
        val groups = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for (category in raw) {
            val n = ChannelNameNormalizer.normalize(category.name)
            val key = n.groupKey.ifEmpty { category.id }
            val entry = groups.getOrPut(key) { n.baseName to mutableListOf() }
            entry.second += category.id
        }
        return groups.map { (key, value) -> CategoryGroup(key, value.first, value.second) }
    }

    /**
     * The category ids to hide from the guide *right now*: the ids behind every group the user
     * marked adult — unless the session is unlocked, in which case nothing is hidden.
     */
    private val hiddenCategoryIds: StateFlow<Set<String>> =
        combine(categoryGroups, settings.hiddenCategories, settings.hiddenUnlocked) { groups, hidden, unlocked ->
            if (unlocked) emptySet()
            else groups.filter { it.key in hidden }.flatMap { it.ids }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The rail's view of categories: hidden ones drop out until the session is unlocked. */
    val visibleCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(categoryGroups, settings.hiddenCategories, settings.hiddenUnlocked) { groups, hidden, unlocked ->
            if (unlocked) groups else groups.filter { it.key !in hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One row on screen = one *logical* channel.
     *
     * [primary] is the best-quality variant and what plays on click; [variants] is every
     * quality of the same channel, for the in-player switch. `UK| BBC ONE SD/HD/FHD/RAW`
     * is one row, not four — the guide has one BBC One, and so should we.
     */
    @androidx.compose.runtime.Immutable
    data class Row(
        val primary: Channel,
        val variants: List<Channel>,
        val now: GuideSlot?,
        val next: GuideSlot?,
        /**
         * Every programme for this channel inside the guide window, start-ordered.
         * The same list instance the shared index already holds — not a copy.
         */
        val programmes: List<GuideSlot>,
    ) {
        val key: Any get() = if (primary.groupKey.isEmpty()) primary.id else primary.groupKey
    }

    /**
     * The guide's left edge: the current half-hour, rounded down. Programme block positions
     * are measured from here. Recomputed on each tick so the grid drifts with real time.
     */
    /** Which day the guide is browsing: 0 = today, 1 = tomorrow… so it pages forward like Sky Q. */
    private val _guideDayOffset = MutableStateFlow(0)
    val guideDayOffset: StateFlow<Int> = _guideDayOffset.asStateFlow()

    /** Page the guide a day forward/back (clamped to today…+[GUIDE_MAX_DAYS]), or jump back to now. */
    fun nudgeGuideDay(delta: Int) {
        _guideDayOffset.value = (_guideDayOffset.value + delta).coerceIn(0, GUIDE_MAX_DAYS)
    }

    fun guideToNow() { _guideDayOffset.value = 0 }

    val windowStartMillis: StateFlow<Long> =
        combine(nowTick.map { it - it % HALF_HOUR_MILLIS }, _guideDayOffset) { base, day ->
            // Today tracks the current half-hour so live is always on screen; a future day starts
            // at that day's local midnight, so the whole day's schedule is browsable and drifts
            // with real time only on the "today" page.
            if (day <= 0) base else startOfLocalDay(base) + day * DAY_MILLIS
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            System.currentTimeMillis().let { it - it % HALF_HOUR_MILLIS },
        )

    private fun startOfLocalDay(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private data class RowsKey(
        val categoryIds: List<String>?,
        val favs: Boolean,
        val query: String,
        val hiddenIds: Set<String>,
    )

    /**
     * Where the time-grid should load a full day of programmes. The channel list itself never
     * waits on this — [rows] publishes channels first. Moves inside the current slice are ignored
     * so a d-pad step does not re-query.
     */
    private val guideFocusIndex = MutableStateFlow(0)

    fun setGuideFocus(index: Int) {
        if (index < 0) return
        val current = guideFocusIndex.value
        if (index == current) return
        val margin = GUIDE_SLICE_ROWS / 4
        if (index in (current - margin)..(current + margin)) return
        guideFocusIndex.value = index
    }

    /** Now/next already fetched for [windowStartMillis], so a category change does not blank the guide. */
    private var nowNextCacheWindow = Long.MIN_VALUE
    private val nowNextCache = HashMap<String, List<GuideSlot>>()

    /** Guards the start-up loading bar so it shows once, on the first build, not on every tap. */
    @Volatile
    private var guideBuilt = false

    private data class GuideRequest(
        val key: RowsKey,
        val source: Long?,
        val windowStart: Long,
        val stamp: Int,
    ) {
        /** Same channels and day, ignoring [stamp] so a guide sync can refresh in place. */
        fun sameListing(other: GuideRequest): Boolean =
            key == other.key && source == other.source && windowStart == other.windowStart
    }

    /**
     * Bumped after the programme table settles. The first count is ignored — the guide read below
     * already sees what's on disk — and later writes (a guide sync) trigger one batched re-read
     * instead of a query that materialises the whole window.
     */
    private val programmeStamp = MutableStateFlow(0)

    init {
        watchProgrammeTable()
        viewModelScope.launch {
            for (source in graph.sourceRepository.enabled()) {
                graph.catalogRepository.repairLiveCategories(source.id)
            }
        }
        viewModelScope.launch {
            combine(categoryGroups, graph.catalogRepository.observeChannelCount()) { groups, count ->
                groups to count
            }.collect { (groups, count) ->
                if (!autoPickedWideList && count > GUIDE_LIST_CAP &&
                    selectedCategory.value == null && groups.isNotEmpty()
                ) {
                    autoPickedWideList = true
                    selectedCategory.value = groups.first().key
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun watchProgrammeTable() {
        viewModelScope.launch {
            graph.epgRepository.observeProgrammeCount()
                .drop(1)
                .debounce(PROGRAMME_STAMP_DEBOUNCE_MILLIS)
                .collect { programmeStamp.value = it }
        }
    }

    /** Last large-list fill that completed. A favourite toggle re-emits channels and must not scan the guide again. */
    private var filledRequest: GuideRequest? = null

    /** Set once, so a huge list opens on a category and "All" stays available afterwards. */
    private var autoPickedWideList = false

    /** The list asks for the next page when the user reaches the bottom. */
    private val moreChannels = Signal<Unit>(Signal.CONFLATED)

    fun loadMoreChannels() {
        moreChannels.trySend(Unit)
    }

    /** True once the current guide query has answered, including an empty page. */
    private val _rowsSettled = MutableStateFlow(false)
    val rowsSettled: StateFlow<Boolean> = _rowsSettled.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<Row>> =
        combine(selectedCategory, favouritesOnly, query, categoryGroups, hiddenCategoryIds) {
                category, favs, q, groups, hidden ->
            val ids = category?.let { key -> groups.firstOrNull { it.key == key }?.ids }
            RowsKey(ids, favs, q, hidden)
        }
            .combine(selectedSource) { key, source -> key to source }
            .combine(windowStartMillis) { (key, source), window -> Triple(key, source, window) }
            .combine(programmeStamp) { (key, source, window), stamp ->
                GuideRequest(key, source, window, stamp)
            }
            .combine(StatusBus.snapshot) { request, snap -> request to snap }
            .flatMapLatest { (request, snap) ->
                // Channels first, then the guide in id batches. Deliberately NOT combined with the
                // minute tick: a tick landing mid-build would cancel and restart this via
                // flatMapLatest. "Now" is captured per build; progress bars use the screen clock.
                // While channels are still being imported, do not subscribe to the table.
                if (snap?.step == LoadStep.CHANNELS) {
                    _rowsSettled.value = false
                    flowOf(emptyList())
                } else {
                    _rowsSettled.value = false
                    channelsFor(request).flatMapLatest { channels -> guideRows(request, channels) }
                        .onEach { _rowsSettled.value = true }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The channel query for the current rail selection. Above [GUIDE_LIST_CAP] rows, "All" is not
     * subscribed and a category is loaded a page at a time.
     */
    private fun channelsFor(request: GuideRequest): kotlinx.coroutines.flow.Flow<List<Channel>> {
        val key = request.key
        return when {
            key.query.isNotBlank() -> graph.catalogRepository.searchChannels(key.query)
            key.favs -> graph.catalogRepository.observeFavouriteChannels()
            else -> flow {
                val ids = key.categoryIds
                val count = if (ids != null) {
                    graph.catalogRepository.countInCategories(request.source, ids)
                } else {
                    graph.catalogRepository.countVisible(request.source)
                }
                when {
                    ids == null && count > GUIDE_LIST_CAP -> emit(emptyList())
                    ids != null && count > GUIDE_LIST_CAP -> emitAll(pagedChannels(request.source, ids))
                    ids != null -> emitAll(graph.catalogRepository.observeChannelsIn(ids))
                    else -> emitAll(graph.catalogRepository.observeChannels(request.source))
                }
            }
        }
    }

    private fun pagedChannels(
        sourceId: Long?,
        categoryIds: List<String>,
    ): kotlinx.coroutines.flow.Flow<List<Channel>> = flow {
        val loaded = ArrayList<Channel>()
        var afterSort = Int.MIN_VALUE
        var afterId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = graph.catalogRepository.pageChannels(
                sourceId,
                categoryIds,
                afterSort,
                afterId,
                GUIDE_LIST_CAP,
            )
            if (page.isEmpty()) {
                if (loaded.isEmpty()) emit(emptyList())
                break
            }
            loaded.addAll(page)
            emit(loaded.toList())
            if (page.size < GUIDE_LIST_CAP) break
            val last = page.last()
            afterSort = last.sortIndex
            afterId = last.id
            moreChannels.receive()
        }
    }

    /**
     * Publishes the channel list immediately, then fills now/next from the guide in batches.
     * A small category also keeps the full day on each row. A large one keeps only now/next,
     * plus a full day for the rows around [guideFocusIndex] while the time-grid is showing.
     * A failed read logs and leaves the channels on screen — it must not fail the view-model scope.
     */
    private fun guideRows(request: GuideRequest, channels: List<Channel>): kotlinx.coroutines.flow.Flow<List<Row>> = flow {
        val key = request.key
        val source = request.source
        val scoped = if (source == null) channels else channels.filter { it.sourceId == source }
        val visible = if (key.hiddenIds.isEmpty()) scoped else scoped.filter { it.categoryId !in key.hiddenIds }
        LoadTrace.mark("guideRows start channels=${channels.size} visible=${visible.size}")
        val now = System.currentTimeMillis()
        if (nowNextCacheWindow != request.windowStart) {
            nowNextCache.clear()
            nowNextCacheWindow = request.windowStart
        }
        val firstFill = !guideBuilt
        LoadTrace.mark("guideRows build visible=${visible.size} firstFill=$firstFill")
        val initial = buildRows(visible, nowNextCache, now, programmesByEpg = emptyMap())
        LoadTrace.mark("guideRows built rows=${initial.size}")
        // A favourite toggle or a settled guide sync re-enters with the same listing. Don't
        // publish an empty timeline over the rows already on screen. A new category or day has
        // nothing to keep, so it emits immediately and the spinner can leave.
        val sameListing = filledRequest?.sameListing(request) == true
        if (visible.isEmpty() || !sameListing || nowNextCache.isEmpty()) emit(initial)
        if (visible.isEmpty()) {
            if (!guideBuilt) clearGuideStatus()
            return@flow
        }
        val keepFull = initial.size <= FULL_GUIDE_ROW_LIMIT
        val followFocus: suspend kotlinx.coroutines.flow.FlowCollector<List<Row>>.() -> Unit = {
            combine(guideFocusIndex, settings.channelLayout) { index, layout -> index to layout }
                .distinctUntilChanged()
                .collect { (index, layout) ->
                    val programmes = if (layout != AppSettings.ChannelLayout.GRID) {
                        emptyMap()
                    } else {
                        try {
                            programmesFor(request.windowStart, focusSlice(initial, index))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            LoadTrace.mark("guide window failed", t)
                            Log.w("OpenTV", "Guide window failed", t)
                            if (t !is Exception) throw t
                            emptyMap()
                        }
                    }
                    emit(buildRows(visible, nowNextCache, now, programmesByEpg = programmes))
                }
        }
        if (!keepFull && filledRequest == request) {
            try {
                followFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LoadTrace.mark("guide focus failed", t)
                Log.w("OpenTV", "Guide load failed", t)
                if (t !is Exception) throw t
            }
            return@flow
        }
        try {
            if (firstFill) StatusBus.set(sizeMessage(visible.size), 0f)
            val ids = LinkedHashSet<String>()
            for (channel in visible) ids.addAll(channel.epgCandidates)
            val full = if (keepFull) HashMap<String, MutableList<GuideSlot>>() else null
            if (ids.isNotEmpty()) {
                graph.epgRepository.forEachGuideChunk(
                    request.windowStart,
                    request.windowStart + GUIDE_LOOKAHEAD_MILLIS,
                    ids,
                ) { slots, index, count ->
                    retainNowNext(nowNextCache, slots, now)
                    if (full != null) appendSlots(full, slots)
                    val last = index == count - 1
                    if (index == 0 || last || (index + 1) % 10 == 0) {
                        LoadTrace.mark("guide chunk ${index + 1}/$count slots=${slots.size} keepFull=$keepFull")
                    }
                    // Refresh of a large list emits once, from the focus slice below, so the
                    // timeline stays up while now/next is re-read. The first fill emits as batches
                    // land so the "now" line appears before the last batch.
                    val emitNow = when {
                        !firstFill && !keepFull -> false
                        !firstFill -> last
                        else -> last || keepFull || (index + 1) % 8 == 0
                    }
                    if (!emitNow) return@forEachGuideChunk
                    if (firstFill) StatusBus.setProgress((index + 1f) / count)
                    val programmes = if (keepFull && last) freeze(full!!) else emptyMap()
                    emit(buildRows(visible, nowNextCache, now, programmesByEpg = programmes))
                }
            }
            if (firstFill) clearGuideStatus()
            if (!keepFull) filledRequest = request
            if (keepFull) return@flow
            followFocus()
        } catch (e: CancellationException) {
            if (firstFill) StatusBus.set(null)
            throw e
        } catch (t: Throwable) {
            LoadTrace.mark("guideRows failed", t)
            Log.w("OpenTV", "Guide load failed", t)
            if (firstFill) clearGuideStatus()
            if (t !is Exception) throw t
        }
    }

    /** Full-day slots for [rows], read in id batches and dropped once grouped. */
    private suspend fun programmesFor(windowStart: Long, rows: List<Row>): Map<String, List<GuideSlot>> {
        val ids = LinkedHashSet<String>()
        for (row in rows) {
            for (channel in row.variants) ids.addAll(channel.epgCandidates)
        }
        if (ids.isEmpty()) return emptyMap()
        val full = HashMap<String, MutableList<GuideSlot>>()
        graph.epgRepository.forEachGuideChunk(
            windowStart,
            windowStart + GUIDE_LOOKAHEAD_MILLIS,
            ids,
        ) { slots, _, _ ->
            appendSlots(full, slots)
        }
        return freeze(full)
    }

    private fun focusSlice(rows: List<Row>, anchor: Int): List<Row> {
        if (rows.size <= GUIDE_SLICE_ROWS) return rows
        val half = GUIDE_SLICE_ROWS / 2
        val start = (anchor - half).coerceIn(0, rows.size - GUIDE_SLICE_ROWS)
        return rows.subList(start, start + GUIDE_SLICE_ROWS)
    }

    private fun clearGuideStatus() {
        guideBuilt = true
        StatusBus.set(null)
    }

    /** Keeps the live programme and the next one; the rest of the batch is discarded. */
    private fun retainNowNext(into: MutableMap<String, List<GuideSlot>>, slots: List<GuideSlot>, now: Long) {
        var i = 0
        while (i < slots.size) {
            val id = slots[i].epgChannelId
            var live: GuideSlot? = null
            var next: GuideSlot? = null
            while (i < slots.size && slots[i].epgChannelId == id) {
                val slot = slots[i]
                if (live == null && slot.isLiveAt(now)) live = slot
                if (next == null && slot.startUtcMillis > now) next = slot
                i++
            }
            val kept = when {
                live != null && next != null -> listOf(live, next)
                live != null -> listOf(live)
                next != null -> listOf(next)
                else -> emptyList()
            }
            if (kept.isNotEmpty()) into[id] = kept
        }
    }

    private fun appendSlots(into: MutableMap<String, MutableList<GuideSlot>>, slots: List<GuideSlot>) {
        for (slot in slots) {
            into.getOrPut(slot.epgChannelId) { ArrayList() }.add(slot)
        }
    }

    /** Shares the lists as-is. Call only after nothing else will append to them. */
    private fun freeze(full: Map<String, MutableList<GuideSlot>>): Map<String, List<GuideSlot>> {
        val snapshot = HashMap<String, List<GuideSlot>>(full.size)
        for ((id, list) in full) snapshot[id] = list
        return snapshot
    }

    private fun buildRows(
        channels: List<Channel>,
        byEpgChannel: Map<String, List<GuideSlot>>,
        now: Long,
        programmesByEpg: Map<String, List<GuideSlot>>? = null,
    ): List<Row> {
        // Quality-variant folding is pure list work. Guide data arrives already grouped by
        // epg channel id, in start order, from the chunked query — never from one list of the
        // whole window.
        val groups = LinkedHashMap<Any, MutableList<Channel>>()
        for (channel in channels) {
            val key: Any = channel.groupKey.ifEmpty { channel.id }
            groups.getOrPut(key) { mutableListOf() } += channel
        }

        return groups.values.map { group ->
            group.sortByDescending { it.qualityRank }
            // Only genuinely different qualities are switchable; identical-quality dupes
            // (same stream in two categories) collapse to one, so no false "2 qualities".
            val variants = distinctByQuality(group)
            val primary = variants.first()

            // Walk every variant's guide-id candidates (override → provider → matched)
            // and use the first that actually has programmes. An id that never lights up
            // must not shadow a sibling variant whose id does.
            val guide = guideFor(variants, byEpgChannel)
            val programmes = if (programmesByEpg != null) guideFor(variants, programmesByEpg) else guide

            Row(
                primary = primary,
                variants = variants,
                now = guide.firstOrNull { it.isLiveAt(now) },
                next = guide.firstOrNull { it.startUtcMillis > now },
                programmes = programmes,
            )
        }
    }

    private fun guideFor(variants: List<Channel>, byEpg: Map<String, List<GuideSlot>>): List<GuideSlot> =
        variants.flatMap { it.epgCandidates }.firstNotNullOfOrNull { byEpg[it] }.orEmpty()

    /** A friendly, size-aware line for the load — a small provider gets a quick word, a huge one
     * gets a "bear with me". Used at start-up so the wait always says what it's doing. */
    private fun sizeMessage(count: Int): String = when {
        count <= 0 -> "Building the guide…"
        count < 2000 -> "Loading $count channels — a small one, this'll be quick."
        count < 8000 -> "Loading $count channels — a fair few, give me a moment…"
        else -> "Loading $count channels — a big one, bear with me, I'm on it…"
    }

    val favourites: StateFlow<List<Channel>> =
        graph.catalogRepository.observeFavouriteChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the catalogue has any channels yet, as a tri-state that stops the home screen
     * spinning forever when a provider fails to load:
     *  - null  → the count query hasn't returned; still checking, keep showing the loader.
     *  - true  → channels are on disk, so an empty [rows] just means the guide is still building.
     *  - false → confirmed empty; if a provider is configured the last sync failed or returned
     *            nothing, so the screen shows a recoverable error instead of an endless spinner.
     */
    val channelsPresent: StateFlow<Boolean?> =
        graph.catalogRepository.observeChannelCount()
            .map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many channels are on disk, for the "pick a category" line on a huge list. */
    val visibleChannelCount: StateFlow<Int> =
        graph.catalogRepository.observeChannelCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** "All" on a list bigger than [GUIDE_LIST_CAP]: the grid is not subscribed. */
    val catalogueWide: StateFlow<Boolean> = combine(
        selectedCategory,
        favouritesOnly,
        query,
        visibleChannelCount,
    ) { category, favs, text, count ->
        category == null && !favs && text.isBlank() && count > GUIDE_LIST_CAP
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun selectCategory(id: String?) {
        favouritesOnly.value = false
        selectedCategory.value = id
    }

    fun selectFavourites() {
        favouritesOnly.value = true
        selectedCategory.value = null
    }

    /** Switch the live guide's provider filter; the category resets since categories differ per source. */
    fun selectSource(sourceId: Long?) {
        selectedSource.value = sourceId
        selectedCategory.value = null
        favouritesOnly.value = false
    }

    fun search(text: String) { query.value = text }

    // ---- Dedicated channel search --------------------------------------------------------------
    // Kept separate from the guide's `rows` on purpose: the guide fills now/next after the
    // channel list, which is the wrong amount of work for every keystroke. This path is
    // channel-only — no programme window — debounced, and capped by the DAO's LIMIT.
    private val searchInput = MutableStateFlow("")

    fun setSearchQuery(text: String) { searchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: StateFlow<List<Row>> =
        searchInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList())
                else graph.catalogRepository.searchChannels(q)
                    // No EPG here — group channels into rows with no now/next. Finding and
                    // playing the channel is the job; guide detail would cost the slow query.
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun tick() { nowTick.value = System.currentTimeMillis() }

    // ---- Channel manager (search, then hide/show or favourite) ---------------------------------
    private val managerInput = MutableStateFlow("")

    fun setManagerQuery(text: String) { managerInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val managerResults: StateFlow<List<Row>> =
        managerInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList())
                else graph.catalogRepository.searchChannelsIncludingHidden(q)
                    .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Channel manager: browse by category ---------------------------------------------------
    // Deliberately independent of the guide's `selectedCategory`/`rows`: browsing here never moves
    // what Live TV is showing. Two differences from the guide feed — it can be scoped to a single
    // provider (cardiodoc's "keep sources separate"), and it includes HIDDEN channels so they can
    // be brought back. Always scoped to one category, never the whole catalogue, so the right pane
    // stays a size a remote can scroll (no flat 20k-row list).

    /** Provider filter for the manager. null = every source ("All sources"). */
    val managerSelectedSource = MutableStateFlow<Long?>(null)

    /** The [CategoryGroup.key] whose channels fill the manager's right pane; null = none picked. */
    val managerSelectedCategory = MutableStateFlow<String?>(null)

    /** Providers, so the manager can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The manager's category rail: like [categoryGroups] but scoped to [managerSelectedSource], and
     * NOT filtered by the adult/hidden-category setting — the manager shows every category so a
     * hidden one's channels can still be reached and un-hidden.
     */
    val managerCategoryGroups: StateFlow<List<CategoryGroup>> =
        combine(
            graph.catalogRepository.observeCategoriesWithChannels(),
            managerSelectedSource,
        ) { raw, sourceId ->
            val scoped = if (sourceId == null) raw else raw.filter { it.sourceId == sourceId }
            foldCategories(scoped)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val moreManagerChannels = Signal<Unit>(Signal.CONFLATED)
    private val managerLock = Any()
    private val managerLoaded = ArrayList<Channel>()
    private val managerListing = MutableStateFlow<List<Channel>>(emptyList())

    /**
     * Channels in the selected manager category, including hidden ones. A category above
     * [GUIDE_LIST_CAP] is paged with the same cursor as the guide. Smaller ones stay a live query
     * so a hide or show updates the row.
     */
    val managerRows: StateFlow<List<Row>> =
        managerListing
            .map { channels -> buildRows(channels, emptyMap(), System.currentTimeMillis()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(managerSelectedSource, managerSelectedCategory, managerCategoryGroups) { source, key, groups ->
                val ids = key?.let { k -> groups.firstOrNull { it.key == k }?.ids }
                source to ids
            }
                .distinctUntilChanged()
                .collectLatest { (source, ids) -> loadManagerListing(source, ids) }
        }
    }

    fun loadMoreManagerChannels() {
        moreManagerChannels.trySend(Unit)
    }

    private suspend fun loadManagerListing(source: Long?, ids: List<String>?) {
        publishManager(emptyList())
        if (ids.isNullOrEmpty()) return
        val count = graph.catalogRepository.countInCategoriesIncludingHidden(source, ids)
        if (count <= GUIDE_LIST_CAP) {
            graph.catalogRepository.observeChannelsInIncludingHidden(source, ids).collect { publishManager(it) }
            return
        }
        val loaded = ArrayList<Channel>()
        var afterSort = Int.MIN_VALUE
        var afterId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = graph.catalogRepository.pageChannelsIncludingHidden(
                source, ids, afterSort, afterId, GUIDE_LIST_CAP,
            )
            if (page.isEmpty()) {
                if (loaded.isEmpty()) publishManager(emptyList())
                break
            }
            loaded.addAll(page)
            publishManager(loaded)
            if (page.size < GUIDE_LIST_CAP) break
            val last = page.last()
            afterSort = last.sortIndex
            afterId = last.id
            moreManagerChannels.receive()
        }
    }

    private fun publishManager(channels: List<Channel>) {
        synchronized(managerLock) {
            val hiddenNow = managerLoaded.associate { it.id to it.hidden }
            val merged = if (hiddenNow.isEmpty()) {
                channels
            } else {
                channels.map { channel ->
                    val hidden = hiddenNow[channel.id]
                    if (hidden != null && hidden != channel.hidden) channel.copy(hidden = hidden) else channel
                }
            }
            managerLoaded.clear()
            managerLoaded.addAll(merged)
            managerListing.value = ArrayList(managerLoaded)
        }
    }

    private fun patchManagerHidden(ids: Set<Long>, hidden: Boolean) {
        synchronized(managerLock) {
            for (i in managerLoaded.indices) {
                val channel = managerLoaded[i]
                if (channel.id in ids) managerLoaded[i] = channel.copy(hidden = hidden)
            }
            managerListing.value = ArrayList(managerLoaded)
        }
    }

    /** Switch the manager's provider filter; the category resets since categories differ per source. */
    fun selectManagerSource(sourceId: Long?) {
        managerSelectedSource.value = sourceId
        managerSelectedCategory.value = null
    }

    fun selectManagerCategory(key: String?) { managerSelectedCategory.value = key }

    /** Hide or show a whole logical channel — every quality variant follows. */
    fun setRowHidden(row: Row, hidden: Boolean) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, hidden) }
            patchManagerHidden(row.variants.map { it.id }.toSet(), hidden)
        }
    }

    fun toggleFavourite(row: Row) {
        viewModelScope.launch {
            // Favouriting the row favourites the logical channel: every variant follows,
            // so the choice survives switching quality.
            val target = !row.primary.favourite
            row.variants.forEach { graph.catalogRepository.setChannelFavourite(it.id, target) }
        }
    }

    fun hide(row: Row) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, true) }
        }
    }

    internal companion object {
        const val GUIDE_LOOKAHEAD_MILLIS = 24 * 60 * 60 * 1000L  // a full day fits the grid
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val GUIDE_MAX_DAYS = 6  // browse up to a week out, matching typical XMLTV depth
        const val HALF_HOUR_MILLIS = 30 * 60 * 1000L

        /** At or below this, every row keeps the full day. Above it, only a slice around the focus does. */
        const val FULL_GUIDE_ROW_LIMIT = 1200

        /** Above this, "All" is not loaded and a category is paged. */
        const val GUIDE_LIST_CAP = 2_000

        /** Rows of the time-grid that hold a full day when the category is larger than [FULL_GUIDE_ROW_LIMIT]. */
        const val GUIDE_SLICE_ROWS = 80

        /** Wait for a guide sync's batched writes to pause before reading the table again. */
        const val PROGRAMME_STAMP_DEBOUNCE_MILLIS = 1_500L
    }
}

/** Backs the Guide settings screen: feed list, toggles, custom URLs, match report. */
class EpgViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    data class UiState(
        val feeds: List<EpgFeed> = emptyList(),
        val syncing: Boolean = false,
        val statusLine: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { graph.epgRepository.ensureFeeds() }
        viewModelScope.launch {
            graph.epgRepository.observeFeeds().collect { feeds ->
                _ui.value = _ui.value.copy(feeds = feeds)
            }
        }
    }

    fun setEnabled(feed: EpgFeed, enabled: Boolean) {
        viewModelScope.launch {
            graph.epgRepository.setFeedEnabled(feed.id, enabled)
            if (enabled) refresh() // turning a guide on should visibly do something
        }
    }

    fun addCustom(name: String, url: String) {
        viewModelScope.launch {
            graph.epgRepository.addCustomFeed(name, url)
            refresh()
        }
    }

    fun remove(feed: EpgFeed) {
        viewModelScope.launch { graph.epgRepository.removeFeed(feed) }
    }

    fun refresh() {
        if (_ui.value.syncing) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, statusLine = "Downloading guides…")
            val summary = graph.epgRepository.syncAll(System.currentTimeMillis(), force = true)
            _ui.value = _ui.value.copy(
                syncing = false,
                statusLine = "Guide matched ${summary.channelsMatched} of " +
                    "${summary.channelsTotal} channels" +
                    if (summary.feedsFailed > 0) " · ${summary.feedsFailed} feed(s) failed" else "",
            )
        }
    }
}

class VodViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    /** One card in the Continue Watching shelf — a movie or episode with somewhere left to go. */
    data class ResumeItem(
        val mediaKey: String,
        val title: String,
        val posterUrl: String?,
        val streamUrl: String,
        val progress: Float,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<List<ResumeItem>> =
        settings.activeProfileId
            .flatMapLatest { pid -> graph.playbackPositions.observeRecent(pid) }
            .mapLatest { positions -> positions.filter { !it.isFinished }.mapNotNull { resolveResume(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun resolveResume(pos: PlaybackPosition): ResumeItem? {
        val parts = pos.mediaKey.split(":", limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val progress =
            if (pos.durationMillis > 0) (pos.positionMillis.toFloat() / pos.durationMillis).coerceIn(0f, 1f) else 0f
        return when (parts[0]) {
            "movie" -> graph.catalogRepository.movie(id)?.let {
                ResumeItem(pos.mediaKey, it.displayTitle, it.posterUrl, it.streamUrl, progress)
            }
            "ep" -> graph.catalogRepository.episode(id)?.let {
                ResumeItem(
                    pos.mediaKey,
                    it.title.ifBlank { "S${it.season} E${it.episodeNumber}" },
                    it.stillUrl,
                    it.streamUrl,
                    progress,
                )
            }
            else -> null
        }
    }

    private val movieCategory = MutableStateFlow<String?>(null)
    private val seriesCategory = MutableStateFlow<String?>(null)

    /**
     * The series category whose own channel count is shown on All. Clearing the chip
     * ([selectSeriesCategory] with null) does not clear this: All still names the category the
     * user just left, and never counts every series category together.
     */
    private val seriesLibraryCategory = MutableStateFlow<String?>(null)

    /** Provider filter for Movies/Shows. null = every source. Surfaced only when there's >1 source. */
    val selectedVodSource = MutableStateFlow<Long?>(null)

    /** Providers, so Movies/Shows can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Category chips scoped to the chosen provider: pick a provider and you see only its categories,
    // and since a category id belongs to one provider, the grid you then open is already that
    // provider's titles. null folds categories across every provider ("All sources").
    val movieCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.MOVIE),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seriesCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.SERIES),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedMovieCategory: StateFlow<String?> = movieCategory.asStateFlow()
    val selectedSeriesCategory: StateFlow<String?> = seriesCategory.asStateFlow()

    /** One page of a category. [Loading] until the first query returns. */
    sealed interface Shelf {
        data object Loading : Shelf
        data object Empty : Shelf
        data class Channels(val items: List<Channel>, val complete: Boolean) : Shelf
        data class Movies(val items: List<Movie>, val complete: Boolean) : Shelf
        data class Shows(val items: List<Series>, val complete: Boolean) : Shelf
    }

    data class ShelfLibrary(val count: Int, val wide: Boolean)

    private val moreMoviePosters = Signal<Unit>(Signal.CONFLATED)
    private val moreSeriesPosters = Signal<Unit>(Signal.CONFLATED)
    @Volatile private var autoPickedMovies = false
    @Volatile private var autoPickedSeries = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val movieLibrary: StateFlow<ShelfLibrary> = movieCategories
        .flatMapLatest { cats -> flow { emit(librarySize(cats)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfLibrary(0, false))

    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesLibrary: StateFlow<ShelfLibrary> = combine(seriesCategories, seriesLibraryCategory) { cats, id ->
        cats to id
    }
        .flatMapLatest { (cats, id) -> flow { emit(seriesLibrarySize(cats, id)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfLibrary(0, false))

    @OptIn(ExperimentalCoroutinesApi::class)
    val movieShelf: StateFlow<Shelf> = movieCategory
        .flatMapLatest { id -> if (id == null) flowOf(Shelf.Empty) else movieShelfFor(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Shelf.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesShelf: StateFlow<Shelf> = seriesCategory
        .flatMapLatest { id -> if (id == null) flowOf(Shelf.Empty) else seriesShelfFor(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Shelf.Loading)

    fun loadMoreMovies() { moreMoviePosters.trySend(Unit) }

    fun loadMoreSeries() { moreSeriesPosters.trySend(Unit) }

    private suspend fun librarySize(categories: List<Category>): ShelfLibrary {
        val table = runCatching { graph.catalogRepository.movieCount() }.getOrDefault(0)
        val inChannels = if (categories.isEmpty()) 0 else runCatching {
            graph.catalogRepository.countInCategories(null, categories.map { it.id })
        }.getOrDefault(0)
        val total = table + inChannels
        return ShelfLibrary(total, total > ChannelsViewModel.GUIDE_LIST_CAP)
    }

    /**
     * Series All. The Xtream catalogue is [CatalogRepository.seriesCount]. An M3U shelf keeps
     * episodes in `channels` and that table stays at zero — counting every series category at
     * once is the query that failed and left the tab empty. The number is then one category.
     */
    private suspend fun seriesLibrarySize(categories: List<Category>, categoryId: String?): ShelfLibrary {
        val table = runCatching { graph.catalogRepository.seriesCount() }.getOrDefault(0)
        if (table > 0) {
            return ShelfLibrary(table, table > ChannelsViewModel.GUIDE_LIST_CAP)
        }
        if (categories.isEmpty()) return ShelfLibrary(0, false)
        val id = if (categoryId != null && categories.any { it.id == categoryId }) {
            categoryId
        } else {
            categories.first().id
        }
        val count = runCatching {
            graph.catalogRepository.countInCategories(null, listOf(id))
        }.getOrDefault(0)
        return if (count > 0) ShelfLibrary(count, true) else ShelfLibrary(0, false)
    }

    private fun movieShelfFor(id: String): kotlinx.coroutines.flow.Flow<Shelf> = flow {
        emit(Shelf.Loading)
        val channels = graph.catalogRepository.countInCategories(null, listOf(id))
        if (channels > 0) emitAll(pagedChannels(id, moreMoviePosters))
        else if (graph.catalogRepository.countMoviesIn(id) == 0) emit(Shelf.Empty)
        else emitAll(pagedMovies(id))
    }

    private fun seriesShelfFor(id: String): kotlinx.coroutines.flow.Flow<Shelf> = flow {
        emit(Shelf.Loading)
        val channels = graph.catalogRepository.countInCategories(null, listOf(id))
        if (channels > 0) emitAll(pagedChannels(id, moreSeriesPosters))
        else if (graph.catalogRepository.countSeriesIn(id) == 0) emit(Shelf.Empty)
        else emitAll(pagedShows(id))
    }

    private fun pagedChannels(
        categoryId: String,
        more: Signal<Unit>,
    ): kotlinx.coroutines.flow.Flow<Shelf> = flow {
        val loaded = ArrayList<Channel>()
        var afterSort = Int.MIN_VALUE
        var afterId = -1L
        val cap = ChannelsViewModel.GUIDE_LIST_CAP
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = graph.catalogRepository.pageChannels(null, listOf(categoryId), afterSort, afterId, cap)
            if (page.isEmpty()) {
                emit(if (loaded.isEmpty()) Shelf.Empty else Shelf.Channels(loaded.toList(), complete = true))
                break
            }
            loaded.addAll(page)
            val complete = page.size < cap
            emit(Shelf.Channels(loaded.toList(), complete))
            if (complete) break
            val last = page.last()
            afterSort = last.sortIndex
            afterId = last.id
            more.receive()
        }
    }

    private fun pagedMovies(categoryId: String): kotlinx.coroutines.flow.Flow<Shelf> = flow {
        val loaded = ArrayList<Movie>()
        var afterAdded = Long.MAX_VALUE
        var afterId = Long.MAX_VALUE
        val cap = ChannelsViewModel.GUIDE_LIST_CAP
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = graph.catalogRepository.pageMovies(categoryId, afterAdded, afterId, cap)
            if (page.isEmpty()) {
                emit(if (loaded.isEmpty()) Shelf.Empty else Shelf.Movies(loaded.toList(), complete = true))
                break
            }
            loaded.addAll(page)
            val complete = page.size < cap
            emit(Shelf.Movies(loaded.toList(), complete))
            if (complete) break
            val last = page.last()
            afterAdded = last.addedMillis
            afterId = last.id
            moreMoviePosters.receive()
        }
    }

    private fun pagedShows(categoryId: String): kotlinx.coroutines.flow.Flow<Shelf> = flow {
        val loaded = ArrayList<Series>()
        var afterAdded = Long.MAX_VALUE
        var afterId = Long.MAX_VALUE
        val cap = ChannelsViewModel.GUIDE_LIST_CAP
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = graph.catalogRepository.pageSeries(categoryId, afterAdded, afterId, cap)
            if (page.isEmpty()) {
                emit(if (loaded.isEmpty()) Shelf.Empty else Shelf.Shows(loaded.toList(), complete = true))
                break
            }
            loaded.addAll(page)
            val complete = page.size < cap
            emit(Shelf.Shows(loaded.toList(), complete))
            if (complete) break
            val last = page.last()
            afterAdded = last.addedMillis
            afterId = last.id
            moreSeriesPosters.receive()
        }
    }

    fun selectMovieCategory(id: String?) { movieCategory.value = id }

    fun selectSeriesCategory(id: String?) {
        seriesCategory.value = id
        if (id != null) seriesLibraryCategory.value = id
    }

    /** True when at least one Stremio add-on is configured — gates the "add-on sources" button. */
    val hasAddons: StateFlow<Boolean> =
        settings.stremioAddons
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.stremioAddons.value.isNotEmpty())

    /**
     * The streams the configured add-ons offer for [movie], resolved through the user's TMDB key for
     * the IMDb id. Empty when there's no key, no add-ons, no IMDb match, or nothing came back. Each
     * add-on is queried independently so one failing doesn't sink the rest.
     */
    suspend fun addonStreams(movie: Movie): List<StremioStream> {
        val addons = settings.stremioAddons.value
        if (addons.isEmpty()) return emptyList()
        val imdb = graph.catalogRepository.imdbIdFor(movie) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            addons.flatMap { addon ->
                runCatching { graph.stremioClient.streams(addon.manifestUrl, addon.name, "movie", imdb) }
                    .getOrDefault(emptyList())
            }
        }
    }

    /** Switch the Movies/Shows provider filter; category selections reset since they differ per source. */
    fun selectVodSource(sourceId: Long?) {
        selectedVodSource.value = sourceId
        movieCategory.value = null
        seriesCategory.value = null
        seriesLibraryCategory.value = null
        autoPickedMovies = false
        autoPickedSeries = false
    }

    // ---- Netflix-style home rows ----------------------------------------------------------------
    // "Recently added" is reactive: it fills in live as a VOD sync lands. The computed feeds
    // (recommended, by-genre) are held in the StateFlows below and (re)built by [loadHomeFeeds] from
    // one shared scan of the library — guarded so opening the tab twice, or recomposing, does not
    // re-scan a 20k-title catalogue; it recomputes only on a profile change or catalogue growth.

    val recentlyAddedMovies: StateFlow<List<Movie>> =
        graph.catalogRepository.recentlyAddedMovies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyAddedSeries: StateFlow<List<Series>> =
        graph.catalogRepository.recentlyAddedSeries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recommendedMovies = MutableStateFlow<List<Movie>>(emptyList())
    val recommendedMovies: StateFlow<List<Movie>> = _recommendedMovies.asStateFlow()

    private val _movieGenreRows = MutableStateFlow<List<GenreGroup<Movie>>>(emptyList())
    val movieGenreRows: StateFlow<List<GenreGroup<Movie>>> = _movieGenreRows.asStateFlow()

    private val _seriesGenreRows = MutableStateFlow<List<GenreGroup<Series>>>(emptyList())
    val seriesGenreRows: StateFlow<List<GenreGroup<Series>>> = _seriesGenreRows.asStateFlow()

    /** The (profile, catalogue) the computed rows were last built for — the redundant-reload guard. */
    private data class HomeFeedsKey(val profileId: Long, val movieCount: Int, val seriesCount: Int)

    @Volatile private var loadedHomeFeeds: HomeFeedsKey? = null

    /** Serialises [loadHomeFeeds] so two near-simultaneous calls (screen open + the profile emit in
     *  `init`) can't both start the heavy scan — the second waits, sees the guard satisfied, returns.
     *  MUST be declared before the `init` block below: `activeProfileId` is a StateFlow, so its
     *  `collect` fires synchronously during construction, and Kotlin initialises properties top to
     *  bottom — declared after `init`, this mutex would still be null when the first collect runs. */
    private val homeFeedsMutex = Mutex()

    init {
        // The home feeds are per profile (Recommended) and per catalogue (the genre rows). Rebuild
        // them when the active profile changes — and once at start. Routed through the guarded
        // [loadHomeFeeds] so a profile switch triggers exactly one library scan, and re-opening the
        // tab with the same profile and an unchanged catalogue triggers none. Reads an empty result
        // until a VOD sync has populated the catalogue; [ensureVodLoaded] re-runs it once one has.
        viewModelScope.launch {
            settings.activeProfileId.collect { loadHomeFeeds() }
        }
        viewModelScope.launch {
            movieCategories.collect { categories ->
                if (autoPickedMovies || movieCategory.value != null || categories.isEmpty()) return@collect
                val size = librarySize(categories)
                val table = runCatching { graph.catalogRepository.movieCount() }.getOrDefault(0)
                if (size.wide || (table == 0 && size.count > 0)) {
                    autoPickedMovies = true
                    movieCategory.value = categories.first().id
                }
            }
        }
        viewModelScope.launch {
            seriesCategories.collect { categories ->
                if (autoPickedSeries || seriesCategory.value != null || categories.isEmpty()) return@collect
                val table = runCatching { graph.catalogRepository.seriesCount() }.getOrDefault(0)
                if (table > ChannelsViewModel.GUIDE_LIST_CAP) {
                    autoPickedSeries = true
                    selectSeriesCategory(categories.first().id)
                    return@collect
                }
                if (table != 0) return@collect
                // M3U episodes stay in `channels` and the series table is empty. One category
                // is enough to know the shelf is there. Counting every series category is the
                // query that failed and left this tab blank.
                val first = categories.first()
                val channels = runCatching {
                    graph.catalogRepository.countInCategories(null, listOf(first.id))
                }.getOrDefault(0)
                if (channels > 0) {
                    autoPickedSeries = true
                    selectSeriesCategory(first.id)
                }
            }
        }
    }

    /**
     * Recomputes the computed home rows (Recommended + by-genre) with bounded queries.
     *
     * The reactive rows (recently-added, continue-watching) keep themselves current, so this only
     * covers the computed feeds. A large library is never read in full: each genre is a `LIKE`
     * with a limit, and Recommended is the most-watched genre of this profile or an empty row.
     * A `(profile, movieCount, seriesCount)` guard skips the work unless the profile changed or
     * the catalogue grew, so re-opening Movies/Shows is free and a post-sync refresh still fills
     * the rows in.
     */
    fun loadHomeFeeds() {
        viewModelScope.launch {
            homeFeedsMutex.withLock {
                val profileId = settings.activeProfileId.value
                val movieCount = runCatching { graph.catalogRepository.movieCount() }.getOrDefault(0)
                val seriesCount = runCatching { graph.catalogRepository.seriesCount() }.getOrDefault(0)

                // Already built for this profile and this exact catalogue? Then a tab re-open or a
                // recomposition must not trigger another full-library scan. Only a profile change or
                // a catalogue whose size moved gets past here.
                val prev = loadedHomeFeeds
                if (prev != null && prev.profileId == profileId &&
                    prev.movieCount == movieCount && prev.seriesCount == seriesCount
                ) return@withLock

                _recommendedMovies.value = runCatching {
                    graph.catalogRepository.recommendedMoviesLimited(profileId)
                }.getOrDefault(emptyList())
                _movieGenreRows.value = runCatching {
                    graph.catalogRepository.movieGenreShelves(profileId, ChannelsViewModel.GUIDE_LIST_CAP)
                }.getOrDefault(emptyList())
                _seriesGenreRows.value = runCatching {
                    graph.catalogRepository.seriesGenreShelves(ChannelsViewModel.GUIDE_LIST_CAP)
                }.getOrDefault(emptyList())

                loadedHomeFeeds = HomeFeedsKey(profileId, movieCount, seriesCount)
            }
        }
    }

    // Movies + series load on demand — the first time the user opens Movies or Shows — rather than
    // up front at login. A provider's 40,000-title VOD list is exactly what makes a first sync
    // crawl, and most sessions only ever watch live TV. Loaded once per app run.
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    @Volatile private var vodRequested = false

    /** How long a fetched VOD catalogue is trusted before a warm launch re-syncs it. */
    private val VOD_TTL_MILLIS = 12L * 60 * 60 * 1000  // 12 hours

    fun ensureVodLoaded() {
        if (vodRequested) return
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = false) }
    }

    /**
     * Force a fresh download of the movies/series catalogue, ignoring the freshness cache. Wired
     * to the pull-to-refresh / refresh action so the user always has a way to pull new titles in
     * before the TTL lapses.
     */
    fun refreshVod() {
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = true) }
    }

    private suspend fun syncVodIfStale(force: Boolean) {
        val now = System.currentTimeMillis()

        // Warm-launch fast path. The catalogue is persisted in Room, so once it has been fetched
        // there is no reason to re-download and re-upsert a 40k-title list on every launch — that
        // was both the multi-minute "Loading movies & shows…" and the bandwidth hog that starved
        // the live preview into buffering. When we synced recently and already have rows, skip the
        // network entirely and just (re)build the home shelves off what is stored.
        if (!force) {
            val haveCatalogue = runCatching {
                graph.catalogRepository.movieCount() + graph.catalogRepository.seriesCount()
            }.getOrDefault(0) > 0
            val last = settings.vodSyncedAtMillis
            val fresh = last > 0 && now - last < VOD_TTL_MILLIS
            if (haveCatalogue && fresh) {
                loadHomeFeeds()
                return
            }
        }

        _vodLoading.value = true
        var synced = false
        CatalogueLoad.tryRun { meter ->
            synced = runCatching {
                val sources = graph.sourceRepository.enabled()
                for (source in sources) {
                    graph.catalogRepository.planLoad(source, meter, includeLive = false, includeVod = true)
                }
                for (source in sources) {
                    if (!graph.catalogRepository.syncVod(source, now, meter)) {
                        error("vod")
                    }
                }
            }.isSuccess
        }
        _vodLoading.value = false
        // Stamp the cache only when the fetch actually succeeded, so a failed sync retries on the
        // next open instead of being remembered as "fresh" and leaving the user with no catalogue.
        if (synced) settings.vodSyncedAtMillis = now
        // The catalogue may have grown — recompute the computed home rows off the fresh data.
        loadHomeFeeds()
    }

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    fun setVodSearchQuery(text: String) { vodSearchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchMovies(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchSeries(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleMovieFavourite(movie: Movie) {
        viewModelScope.launch {
            graph.catalogRepository.setMovieFavourite(movie.id, !movie.favourite)
        }
    }

    /**
     * Episodes are fetched on demand rather than during catalogue sync.
     *
     * Pulling every episode of every series up front is what makes a first sync take twenty
     * minutes on a large provider, and most of it is never looked at.
     */
    fun loadEpisodes(series: Series) {
        viewModelScope.launch {
            val source = graph.sourceRepository.byId(series.sourceId) ?: return@launch
            graph.catalogRepository.ensureEpisodes(source, series.seriesId)
        }
    }

    fun episodes(series: Series) =
        graph.catalogRepository.observeEpisodes(series.sourceId, series.seriesId)

    /** For the series detail screen: look the series up by row id. */
    fun seriesById(id: Long): kotlinx.coroutines.flow.Flow<Series?> =
        kotlinx.coroutines.flow.flow { emit(graph.catalogRepository.series(id)) }

    /** An always-empty episode flow, so the detail screen has something before a series loads. */
    val noEpisodes: kotlinx.coroutines.flow.Flow<List<app.opentv.data.model.Episode>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun movieById(id: Long): Movie? = graph.catalogRepository.movie(id)

    // ---- Detail screens -------------------------------------------------------------------------
    // Thin wrappers over the repository's detail feeds. The screens drive them from `produceState`
    // keyed on the id, so each detail instance owns its own state and a "More like this" hop to
    // another title loads cleanly without two screens fighting over one shared StateFlow.

    /** Loads a movie, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.movieDetail. */
    suspend fun movieDetail(id: Long): Movie? = graph.catalogRepository.movieDetail(id)

    /** Loads a series, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.seriesDetail. */
    suspend fun seriesDetail(id: Long): Series? = graph.catalogRepository.seriesDetail(id)

    /** "More like this" for the movie detail screen. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    /** "More like this" for the series detail screen. */
    suspend fun moreLikeThisSeries(series: Series): List<Series> =
        graph.catalogRepository.moreLikeThisSeries(series)

    /** Every library title (movies + series) featuring a person — the Person screen's grid. */
    suspend fun titlesWithPerson(name: String): List<PersonTitle> =
        graph.catalogRepository.titlesWithPerson(name)

    /** The active profile's saved resume position for a movie/episode, or null — drives Resume vs Watch now. */
    suspend fun resumePosition(mediaKey: String): PlaybackPosition? =
        graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)

    /** Collapses quality variants of a movie list for a browse grid. See CatalogRepository.collapseVariants. */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> =
        graph.catalogRepository.collapseVariants(movies)

    /** Star / un-star a whole series. Wraps the series DAO through the shared database — no data-layer change. */
    fun toggleSeriesFavourite(series: Series) {
        viewModelScope.launch {
            graph.database.series().setFavourite(series.id, !series.favourite)
        }
    }

    /** The user-agent to play a movie/episode with (per source). */
    suspend fun userAgentForSource(sourceId: Long): String =
        graph.sourceRepository.byId(sourceId)?.userAgent ?: "OpenTV/0.1 (Android)"
}

/**
 * Local viewing profiles. No accounts — just names on this device — with the active one stored in
 * [app.opentv.core.AppSettings] so the whole app agrees on whose watch history is in play.
 */
class ProfilesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    val profiles: StateFlow<List<Profile>> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> = settings.activeProfileId

    init {
        // Fresh installs create the table empty (no migration runs), so seed the default profile.
        viewModelScope.launch {
            if (graph.profiles.all().isEmpty()) {
                graph.profiles.insert(Profile(name = "Me", createdAtMillis = 0))
            }
        }
    }

    fun addProfile(name: String) {
        viewModelScope.launch {
            val id = graph.profiles.insert(
                Profile(name = name.trim().ifBlank { "New profile" }, createdAtMillis = 0),
            )
            settings.setActiveProfile(id)
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { graph.profiles.rename(id, name.trim().ifBlank { "Profile" }) }
    }

    fun select(id: Long) { settings.setActiveProfile(id) }

    fun remove(id: Long) {
        // The base profile stays — there is always at least one to be.
        if (id == 1L) return
        viewModelScope.launch {
            graph.profiles.deletePositions(id)
            graph.profiles.delete(id)
            if (settings.activeProfileId.value == id) settings.setActiveProfile(1L)
        }
    }
}

/**
 * Local, device-to-device watch-history sync — no servers of ours.
 *
 * One device shares (a short-lived LAN server behind a six-digit code); the other receives by
 * typing that device's address and code. Only resume positions travel, keyed by stream URL and
 * profile name so they land on the right film and the right person on the other device. Merge is
 * newest-wins per item, so syncing both ways leaves the two devices agreeing.
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val server = SyncServer(viewModelScope)
    private val client = SyncClient(graph.httpClient)

    val serverState: StateFlow<SyncServer.State> = server.state

    sealed interface ReceiveState {
        data object Idle : ReceiveState
        data object Connecting : ReceiveState
        data class Done(val merged: Int) : ReceiveState
        data class Failed(val message: String) : ReceiveState
    }

    private val _receiveState = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    // ---- NAS ("cloud") sync ------------------------------------------------------------------

    private val nas = NasSync(graph)

    /** Whether an SMB/NAS is set up at all — the NAS pane needs one before it can do anything. */
    val smbHost: StateFlow<String> = graph.settings.smbHost
    val nasAutoSync: StateFlow<Boolean> = graph.settings.nasAutoSync

    sealed interface NasState {
        data object Idle : NasState
        data object Syncing : NasState
        data class Done(val message: String) : NasState
        data class Failed(val message: String) : NasState
    }

    private val _nasState = MutableStateFlow<NasState>(NasState.Idle)
    val nasState: StateFlow<NasState> = _nasState.asStateFlow()

    fun setNasAutoSync(enabled: Boolean) = graph.settings.setNasAutoSync(enabled)

    fun syncNas() {
        if (_nasState.value is NasState.Syncing) return
        viewModelScope.launch {
            _nasState.value = NasState.Syncing
            val ctx = getApplication<Application>()
            _nasState.value = when (val result = nas.sync()) {
                is NasSync.Result.Success ->
                    if (result.peers == 0) NasState.Done(ctx.getString(R.string.sync_nas_uploaded))
                    else NasState.Done(ctx.getString(R.string.sync_nas_done, result.merged, result.peers))
                is NasSync.Result.NotConfigured ->
                    NasState.Failed(ctx.getString(R.string.sync_nas_needs_setup))
                is NasSync.Result.Failed ->
                    NasState.Failed(ctx.getString(R.string.sync_nas_failed))
            }
        }
    }

    fun startSharing() {
        viewModelScope.launch { server.start(buildBundleJson()) }
    }

    fun stopSharing() = server.stop()

    fun receive(address: String, code: String) {
        viewModelScope.launch {
            _receiveState.value = ReceiveState.Connecting
            client.pull(address, code)
                .onSuccess { _receiveState.value = ReceiveState.Done(merge(it)) }
                .onFailure { _receiveState.value = ReceiveState.Failed(it.message ?: "Sync failed.") }
        }
    }

    fun resetReceive() { _receiveState.value = ReceiveState.Idle }

    private suspend fun buildBundleJson(): String {
        val names = graph.profiles.all().associate { it.id to it.name }
        val items = graph.playbackPositions.all().mapNotNull { pos ->
            val profileName = names[pos.profileId] ?: return@mapNotNull null
            val parts = pos.mediaKey.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            val streamUrl = when (parts[0]) {
                "movie" -> graph.catalogRepository.movie(id)?.streamUrl
                "ep" -> graph.catalogRepository.episode(id)?.streamUrl
                else -> null
            } ?: return@mapNotNull null
            SyncPosition(profileName, streamUrl, parts[0], pos.positionMillis, pos.durationMillis, pos.updatedAtMillis)
        }
        return Json.encodeToString(SyncBundle.serializer(), SyncBundle(positions = items))
    }

    private suspend fun merge(bundle: SyncBundle): Int {
        var merged = 0
        for (item in bundle.positions) {
            val profileId = profileIdFor(item.profile) ?: continue
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(item.streamUrl)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(item.streamUrl)?.id
                else -> null
            } ?: continue
            val mediaKey = "${item.kind}:$localId"
            val existing = graph.playbackPositions.get(profileId, mediaKey)
            if (existing == null || item.updatedAtMillis > existing.updatedAtMillis) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = profileId,
                        mediaKey = mediaKey,
                        positionMillis = item.positionMillis,
                        durationMillis = item.durationMillis,
                        updatedAtMillis = item.updatedAtMillis,
                    ),
                )
                merged++
            }
        }
        return merged
    }

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }

    override fun onCleared() {
        server.stop()
    }
}

/**
 * Owns the "manage channels from your phone or laptop" web server for the manage screen.
 *
 * Same ownership shape as [SyncViewModel]: the server is bound to [viewModelScope] (so its idle
 * watchdog is torn down with the screen) and stopped in [onCleared]. The screen also starts it on
 * open and stops it on leave, mirroring the phone-pairing lifecycle — so no socket is ever left
 * listening on the user's network once they navigate away.
 */
class WebManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val server = ManagerServer(viewModelScope, graph.catalogRepository, graph.settings, graph.sourceRepository)

    val state: StateFlow<ManagerServer.State> = server.state

    fun start() = server.start()

    fun stop() = server.stop()

    override fun onCleared() {
        server.stop()
    }
}
