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
import app.opentv.core.LongOpenSet
import app.opentv.data.db.CategoryDao
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.MovieDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.db.SeriesDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import java.util.concurrent.TimeUnit
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.M3uGroups
import app.opentv.data.parser.M3uParser
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.parser.fnv64
import app.opentv.data.parser.isSeparatorRow
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps one channel per distinct quality signature, preserving order (so "best first" is
 * preserved when the caller has already sorted by rank).
 *
 * Two streams with the same rank AND the same label are treated as the same quality — one is
 * kept, the rest dropped. This is what collapses "RAW / RAW" and "HD / HD" duplicates down to
 * a single option, and turns a falsely-multi-quality channel back into a single one.
 */
internal fun distinctByQuality(channels: List<app.opentv.data.model.Channel>): List<app.opentv.data.model.Channel> {
    val seen = HashSet<String>()
    return channels.filter { seen.add("${it.qualityRank}|${it.qualityLabel.lowercase()}") }
}

/** A home/detail row: a genre label and the titles under it. Generic so movies and series share it. */
@androidx.compose.runtime.Immutable
data class GenreGroup<T>(val genre: String, val items: List<T>)

/**
 * One library entry a person is credited in — a movie or a series — for the Person screen's mixed
 * poster grid. A thin wrapper so the screen can render one grid yet still route a click to the right
 * detail page (movie vs series) without a second lookup.
 */
sealed interface PersonTitle {
    data class MovieItem(val movie: Movie) : PersonTitle
    data class SeriesItem(val series: Series) : PersonTitle
}

/** One quality variant of a film, with the quality parsed from its name by [ChannelNameNormalizer]. */
data class MovieVariant(val movie: Movie, val qualityLabel: String, val qualityRank: Int)

/** One logical film with its switchable quality tiers, best first — the VOD analogue of a channel group. */
@androidx.compose.runtime.Immutable
data class MovieVariantGroup(
    /** Best-quality variant: what a row shows and plays by default. */
    val primary: Movie,
    /** Every variant (including [primary]), best quality first. */
    val variants: List<MovieVariant>,
) {
    val hasMultipleQualities: Boolean get() = variants.size > 1
}

/** A standalone 4-digit release year (19xx/20xx) as it appears inside a VOD title. */
private val VOD_YEAR = Regex("""\b(19|20)\d{2}\b""")

/**
 * Collapses obvious quality variants of the same film — "The Godfather 1972 HD" and
 * "The Godfather 4K" — into one entry with switchable tiers, the VOD analogue of a channel's
 * quality group. Pure and in-memory: movies carry no stored groupKey, so the key is computed here
 * from the normalised, year-stripped name plus the year (the field, else the one embedded in the
 * name). Group order follows first appearance; variants within a group are best-quality first.
 *
 * It folds only genuine quality variants: edition tokens [ChannelNameNormalizer] does not know
 * (IMAX, EXTENDED, 3D) stay in the name and keep those cuts separate — intended. Two same-named
 * films that both lack any year will merge, an accepted edge for a best-effort collapse.
 */
internal fun collapseMovieVariants(movies: List<Movie>): List<MovieVariantGroup> {
    val groups = LinkedHashMap<String, MutableList<MovieVariant>>()
    for (movie in movies) {
        val embeddedYear = VOD_YEAR.find(movie.name)?.value?.toIntOrNull()
        val bareName = VOD_YEAR.replace(movie.name, " ")
        val normalized = ChannelNameNormalizer.normalize(bareName)
        val year = movie.year ?: embeddedYear
        val key = normalized.groupKey + "|" + (year?.toString() ?: "")
        groups.getOrPut(key) { mutableListOf() }
            .add(MovieVariant(movie, normalized.qualityLabel, normalized.qualityRank))
    }
    return groups.values.map { variants ->
        val ordered = variants.sortedWith(
            compareByDescending<MovieVariant> { it.qualityRank }.thenBy { it.movie.name },
        )
        MovieVariantGroup(primary = ordered.first().movie, variants = ordered)
    }
}

/**
 * Channels, movies and series.
 *
 * Same principle as [EpgRepository]: a refresh that fails must never leave the user with less
 * than they started with. A large playlist is written in batches of a few hundred rows.
 * [ChannelDao.deleteStale] runs only after the last batch succeeds, so a sync that dies halfway
 * keeps both the old rows and whatever was already saved.
 */
class CatalogRepository(
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val positionDao: PlaybackPositionDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {

    /** TMDB back-fill for VOD detail pages, gated on a user-supplied key. See [TmdbClient]. */
    private val tmdb = TmdbClient(http, settings)

    /**
     * The shared client caps a call at five minutes. A large playlist spends that time writing
     * batches between reads, so the cap closes the socket with the catalogue half saved.
     * This client has no overall cap. A socket that stops sending still fails on the read timeout.
     */
    private val downloadHttp = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    sealed interface SyncResult {
        data class Success(
            val channelCount: Int,
            val movieCount: Int,
            val seriesCount: Int,
        ) : SyncResult

        data class Failed(
            val reason: String,
            val cause: Throwable?,
            val tooLarge: Boolean = false,
            /** The body ended early. Channels and categories already written stay. */
            val partial: Boolean = false,
        ) : SyncResult
    }

    fun observeChannels(sourceId: Long? = null, categoryId: String? = null): Flow<List<Channel>> =
        channelDao.observe(sourceId, categoryId)

    fun observeChannelsIn(categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategories(categoryIds)

    suspend fun countVisible(sourceId: Long?): Int = channelDao.countVisible(sourceId)

    suspend fun countInCategories(sourceId: Long?, categoryIds: List<String>): Int =
        if (categoryIds.isEmpty()) 0 else channelDao.countInCategories(sourceId, categoryIds)

    /** One page of a large category. See [ChannelDao.pageInCategories]. */
    suspend fun pageChannels(
        sourceId: Long?,
        categoryIds: List<String>,
        afterSort: Int,
        afterId: Long,
        limit: Int,
    ): List<Channel> = channelDao.pageInCategories(sourceId, categoryIds, afterSort, afterId, limit)

    suspend fun countInCategoriesIncludingHidden(sourceId: Long?, categoryIds: List<String>): Int =
        if (categoryIds.isEmpty()) 0
        else channelDao.countInCategoriesIncludingHidden(sourceId, categoryIds)

    suspend fun pageChannelsIncludingHidden(
        sourceId: Long?,
        categoryIds: List<String>,
        afterSort: Int,
        afterId: Long,
        limit: Int,
    ): List<Channel> =
        channelDao.pageInCategoriesIncludingHidden(sourceId, categoryIds, afterSort, afterId, limit)

    /**
     * Channels in a set of categories INCLUDING hidden ones, optionally scoped to one source — the
     * channel manager's browse feed. Separate from [observeChannelsIn] (which drops `hidden` rows
     * for the guide) because the manager must show hidden channels so they can be un-hidden.
     */
    fun observeChannelsInIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategoriesIncludingHidden(sourceId, categoryIds)

    fun observeFavouriteChannels(): Flow<List<Channel>> = channelDao.observeFavourites()

    /** Reactive number of visible channels on disk — the UI uses this to tell "guide still
     * building" (channels exist) apart from "nothing loaded" (a failed or empty sync). */
    fun observeChannelCount(): Flow<Int> = channelDao.observeVisibleCount()

    fun observeCategories(kind: StreamKind): Flow<List<Category>> = categoryDao.observe(kind)

    fun observeCategoriesWithChannels(): Flow<List<Category>> = categoryDao.observeWithChannels()

    suspend fun countMoviesIn(categoryId: String): Int = movieDao.countInCategory(categoryId)

    suspend fun pageMovies(
        categoryId: String,
        afterAdded: Long,
        afterId: Long,
        limit: Int,
    ): List<Movie> = movieDao.pageInCategory(categoryId, afterAdded, afterId, limit)

    suspend fun countSeriesIn(categoryId: String): Int = seriesDao.countInCategory(categoryId)

    suspend fun pageSeries(
        categoryId: String,
        afterAdded: Long,
        afterId: Long,
        limit: Int,
    ): List<Series> = seriesDao.pageInCategory(categoryId, afterAdded, afterId, limit)

    fun observeMovies(categoryId: String? = null): Flow<List<Movie>> = movieDao.observe(categoryId)

    fun observeSeries(categoryId: String? = null): Flow<List<Series>> = seriesDao.observe(categoryId)

    fun observeEpisodes(sourceId: Long, seriesId: String): Flow<List<Episode>> =
        episodeDao.observeForSeries(sourceId, seriesId)

    fun searchChannels(query: String): Flow<List<Channel>> = channelDao.search(query)

    fun searchChannelsIncludingHidden(query: String): Flow<List<Channel>> =
        channelDao.searchIncludingHidden(query)

    fun searchMovies(query: String): Flow<List<Movie>> = movieDao.search(query)

    fun searchSeries(query: String): Flow<List<Series>> = seriesDao.search(query)

    suspend fun channel(id: Long): Channel? = channelDao.byId(id)

    suspend fun movie(id: Long): Movie? = movieDao.byId(id)

    /**
     * The IMDb id for a film, for Stremio add-on stream lookups. Resolved through the user's TMDB
     * key (using the provider's TMDB id when present, else a title+year search). Null without a key
     * or a match — the add-on feature stays inert rather than guessing.
     */
    suspend fun imdbIdFor(movie: Movie): String? = withContext(Dispatchers.IO) {
        tmdb.imdbId(title = movie.name, year = movie.year, isMovie = true, tmdbId = movie.tmdbId)
    }

    suspend fun episode(id: Long): Episode? = episodeDao.byId(id)

    suspend fun movieByStreamUrl(url: String): Movie? = movieDao.byStreamUrl(url)

    suspend fun episodeByStreamUrl(url: String): Episode? = episodeDao.byStreamUrl(url)

    suspend fun series(id: Long): app.opentv.data.model.Series? = seriesDao.byId(id)

    // ---- Netflix-style home feeds ---------------------------------------------------------------
    // All local: derived from the catalogue already on disk plus the active profile's watch history.
    // The plain catalogue rows (recently added) are Flows so they fill in live as a VOD sync lands;
    // the computed rows (recommended, by-genre, more-like-this) are one-shot suspend reads, cheap
    // enough to recompute on screen open over a few thousand titles (one in-memory pass each — a
    // per-genre LIKE query would multiply round-trips and match substrings). A later UI agent wraps
    // these into home rows and detail screens.

    /** "Recently Added" movies, newest first. Reactive. */
    fun recentlyAddedMovies(limit: Int = 30): Flow<List<Movie>> = movieDao.observeRecentlyAdded(limit)

    /** "Recently Added" series, newest first. Reactive. */
    fun recentlyAddedSeries(limit: Int = 30): Flow<List<Series>> = seriesDao.observeRecentlyAdded(limit)

    /** Every movie, newest first — for a caller that wants to build its own groupings. */
    suspend fun allMovies(): List<Movie> = withContext(Dispatchers.IO) { movieDao.all() }

    /** Every series, newest first. */
    suspend fun allSeries(): List<Series> = withContext(Dispatchers.IO) { seriesDao.all() }

    /** How many movies / series are on disk — a cheap COUNT the home screen uses to tell "the
     *  library grew" from "unchanged since last open" without loading every row. */
    suspend fun movieCount(): Int = withContext(Dispatchers.IO) { movieDao.count() }
    suspend fun seriesCount(): Int = withContext(Dispatchers.IO) { seriesDao.count() }

    /**
     * Movies grouped by genre for the by-genre home rows: the [maxGenres] biggest genres, each with
     * up to [perGenre] titles (newest first). A movie appears under every genre it lists — provider
     * genre strings are frequently multi-valued ("Action, Thriller" / "Action|Thriller"), so they
     * are split on comma and pipe (see [splitGenres]).
     */
    suspend fun moviesByGenre(maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Movie>> =
        withContext(Dispatchers.IO) { groupByGenre(movieDao.all(), Movie::genre, maxGenres, perGenre) }

    /** Series grouped by genre for the by-genre home rows. See [moviesByGenre]. */
    suspend fun seriesByGenre(maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Series>> =
        withContext(Dispatchers.IO) { groupByGenre(seriesDao.all(), Series::genre, maxGenres, perGenre) }

    /**
     * Movies grouped by genre from an ALREADY-LOADED list — the single-scan path the home screen
     * uses. [VodViewModel.loadHomeFeeds] reads [allMovies] once and hands that one list to this, to
     * [recommendedMoviesFrom], etc., so opening Movies scans the (20k-title) table once instead of
     * once per row. Pure grouping, off the main thread. See [moviesByGenre] for the scanning variant.
     */
    suspend fun moviesByGenreFrom(all: List<Movie>, maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Movie>> =
        withContext(Dispatchers.Default) { groupByGenre(all, Movie::genre, maxGenres, perGenre) }

    /** Series grouped by genre from an already-loaded list — the single-scan path. See [moviesByGenreFrom]. */
    suspend fun seriesByGenreFrom(all: List<Series>, maxGenres: Int = 12, perGenre: Int = 30): List<GenreGroup<Series>> =
        withContext(Dispatchers.Default) { groupByGenre(all, Series::genre, maxGenres, perGenre) }

    /**
     * "Recommended for you" — a simple, explainable genre-affinity heuristic, no ML.
     *
     * Tallies the genres of the movies this profile has watched or resumed, then returns the
     * highest-scoring UNWATCHED movies, where a movie's score is how many of its genres the profile
     * favours (ties broken by rating, then recency). With no usable history — a fresh profile, or
     * only watched movies that carry no genre — it falls back to top-rated, then recently-added.
     */
    suspend fun recommendedMovies(profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) { recommendFrom(movieDao.all(), watchedMovieIds(profileId), limit) }

    /**
     * "Recommended for you" from an ALREADY-LOADED movie list — the single-scan path (see
     * [recommendedMovies]). Only the profile's watch history is read from disk here; the movie
     * library is the caller's [allMovies] list, shared with the genre rows so the home screen scans
     * the (20k-title) table once for the whole home feed rather than once per row.
     */
    suspend fun recommendedMoviesFrom(all: List<Movie>, profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) { recommendFrom(all, watchedMovieIds(profileId), limit) }

    /** The movie ids this profile has watched or resumed — the input to the affinity heuristic. */
    private suspend fun watchedMovieIds(profileId: Long): Set<Long> =
        positionDao.forProfile(profileId).mapNotNull { movieIdFromMediaKey(it.mediaKey) }.toSet()

    /** The pure genre-affinity ranking pass shared by [recommendedMovies] and [recommendedMoviesFrom]. */
    private fun recommendFrom(all: List<Movie>, watchedMovieIds: Set<Long>, limit: Int): List<Movie> {
        val byId = all.associateBy { it.id }

        val affinity = HashMap<String, Int>()
        for (id in watchedMovieIds) {
            val watched = byId[id] ?: continue
            for (genre in splitGenres(watched.genre)) affinity[genre] = (affinity[genre] ?: 0) + 1
        }

        val unwatched = all.filter { it.id !in watchedMovieIds }

        fun topRatedFallback(): List<Movie> = unwatched
            .sortedWith(compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis })
            .take(limit)

        if (affinity.isEmpty()) return topRatedFallback()

        val ranked = unwatched
            .map { it to genreScore(it.genre, affinity) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Movie, Int>> { it.second }
                    .thenByDescending { it.first.rating ?: -1.0 }
                    .thenByDescending { it.first.addedMillis },
            )
            .map { it.first }
            .take(limit)

        return ranked.ifEmpty { topRatedFallback() }
    }

    /**
     * "More Like This" for a movie: other titles sharing a genre, most genres in common first,
     * same-source titles preferred, the film itself excluded. Falls back to other titles in the
     * same source/category when the movie has no genre metadata to match on.
     */
    suspend fun moreLikeThis(movie: Movie, limit: Int = 20): List<Movie> = withContext(Dispatchers.IO) {
        val genres = splitGenres(movie.genre).toSet()
        if (genres.isEmpty()) {
            return@withContext movieDao.similarByCategory(movie.sourceId, movie.categoryId, movie.id, limit)
        }
        movieDao.all().asSequence()
            .filter { it.id != movie.id }
            .map { it to sharedGenreCount(it.genre, genres) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Movie, Int>> { it.second }
                    .thenByDescending { it.first.sourceId == movie.sourceId }
                    .thenByDescending { it.first.rating ?: -1.0 },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    // ---- Related by person (Plex-style "click an actor / director") ----------------------------

    /** Movies a person is billed in, best-rated first. Blank query yields nothing. */
    suspend fun moviesWithActor(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesWithActor(it, limit) }.orEmpty()
    }

    /** Movies a person directed, best-rated first. */
    suspend fun moviesByDirector(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesByDirector(it, limit) }.orEmpty()
    }

    /** Series a person is billed in, best-rated first. */
    suspend fun seriesWithActor(name: String, limit: Int = 40): List<Series> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { seriesDao.seriesWithActor(it, limit) }.orEmpty()
    }

    /**
     * Everything in the library featuring a person — the data behind the Person screen. Merges movies
     * they act in, movies they directed and series they act in, de-duplicating movies that credit the
     * same person as both actor and director. Movies (best-rated first) lead, then series.
     */
    suspend fun titlesWithPerson(name: String, perKind: Int = 40): List<PersonTitle> =
        withContext(Dispatchers.IO) {
            val query = name.trim()
            if (query.isEmpty()) return@withContext emptyList()

            val movies = LinkedHashMap<Long, Movie>()
            for (m in movieDao.moviesWithActor(query, perKind)) movies[m.id] = m
            for (m in movieDao.moviesByDirector(query, perKind)) movies.putIfAbsent(m.id, m)

            val orderedMovies = movies.values.sortedWith(
                compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis },
            )
            val series = seriesDao.seriesWithActor(query, perKind)

            buildList(orderedMovies.size + series.size) {
                orderedMovies.forEach { add(PersonTitle.MovieItem(it)) }
                series.forEach { add(PersonTitle.SeriesItem(it)) }
            }
        }

    /** "More Like This" for a series. See [moreLikeThis]. */
    suspend fun moreLikeThisSeries(series: Series, limit: Int = 20): List<Series> = withContext(Dispatchers.IO) {
        val genres = splitGenres(series.genre).toSet()
        if (genres.isEmpty()) {
            return@withContext seriesDao.similarByCategory(series.sourceId, series.categoryId, series.id, limit)
        }
        seriesDao.all().asSequence()
            .filter { it.id != series.id }
            .map { it to sharedGenreCount(it.genre, genres) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Series, Int>> { it.second }
                    .thenByDescending { it.first.sourceId == series.sourceId }
                    .thenByDescending { it.first.rating ?: -1.0 },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    /**
     * Loads a movie and, if its detail fields are still bare, back-fills them from the provider's
     * `get_vod_info` and persists the merged row before returning it. Safe to call whenever a detail
     * screen opens: it runs off the main thread, only touches the network when something is missing,
     * tolerates any failure (returning the row unchanged), and — because it copies the stored row —
     * preserves the favourite flag. Non-Xtream sources are returned as-is.
     */
    suspend fun movieDetail(id: Long): Movie? = withContext(Dispatchers.IO) {
        val movie = movieDao.byId(id) ?: return@withContext null
        var result = movie
        val source = sourceDao.byId(movie.sourceId)

        // 1) Provider back-fill from get_vod_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.movieInfo(source, result.streamId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    director = result.director ?: info.director,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                    durationSeconds = result.durationSeconds ?: info.durationSeconds,
                )
            }
        }

        // 2) TMDB fallback for whatever the provider still left blank — only when the user set a
        //    key, and only while a headline visual field is missing (so it stops re-fetching once
        //    filled). This is the "match Plex" back-fill for posters, backdrops, synopsis and cast.
        if (tmdb.isConfigured() && (result.backdropUrl == null || result.plot == null || result.cast == null)) {
            val meta = runCatching {
                tmdb.movieMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                result = result.copy(
                    posterUrl = result.posterUrl ?: meta.posterUrl,
                    backdropUrl = result.backdropUrl ?: meta.backdropUrl,
                    plot = result.plot ?: meta.overview,
                    cast = result.cast ?: meta.cast,
                    director = result.director ?: meta.director,
                    genre = result.genre ?: meta.genre,
                    tmdbId = result.tmdbId ?: meta.tmdbId,
                    rating = result.rating ?: meta.rating,
                    year = result.year ?: meta.year,
                )
            }
        }

        if (result != movie) movieDao.upsertAll(listOf(result))
        result
    }

    /** Loads a series and lazily back-fills its detail fields from `get_series_info`. See [movieDetail]. */
    suspend fun seriesDetail(id: Long): Series? = withContext(Dispatchers.IO) {
        val series = seriesDao.byId(id) ?: return@withContext null
        var result = series
        val source = sourceDao.byId(series.sourceId)

        // 1) Provider back-fill from get_series_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.seriesInfo(source, result.seriesId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                )
            }
        }

        // 2) TMDB fallback for anything still missing (see [movieDetail]); TMDB has no director for TV.
        if (tmdb.isConfigured() && (result.backdropUrl == null || result.plot == null || result.cast == null)) {
            val meta = runCatching {
                tmdb.seriesMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                result = result.copy(
                    posterUrl = result.posterUrl ?: meta.posterUrl,
                    backdropUrl = result.backdropUrl ?: meta.backdropUrl,
                    plot = result.plot ?: meta.overview,
                    cast = result.cast ?: meta.cast,
                    genre = result.genre ?: meta.genre,
                    tmdbId = result.tmdbId ?: meta.tmdbId,
                    rating = result.rating ?: meta.rating,
                    year = result.year ?: meta.year,
                )
            }
        }

        if (result != series) seriesDao.upsertAll(listOf(result))
        result
    }

    /**
     * Collapses a list of movies (e.g. one category's titles) into logical films with switchable
     * quality tiers — see [collapseMovieVariants]. Pure; hand it whatever list the UI is about to
     * show. Kept here as the discoverable entry point for the VOD UI.
     */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> = collapseMovieVariants(movies)

    // -- feed helpers --

    /** A movie counts as "already enriched" once any headline detail field is set, so [movieDetail]
     *  re-fetches only truly-bare rows. A movie the provider has no metadata for re-fetches each open;
     *  acceptable, and it self-limits the moment anything comes back. */
    private val Movie.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null || director != null

    private val Series.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null

    /** Splits a provider genre string ("Action, Thriller" / "Action|Thriller") into clean genres. */
    private fun splitGenres(raw: String?): List<String> =
        raw?.split(',', '|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

    private fun <T> groupByGenre(
        items: List<T>,
        genreOf: (T) -> String?,
        maxGenres: Int,
        perGenre: Int,
    ): List<GenreGroup<T>> {
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            for (genre in splitGenres(genreOf(item))) {
                buckets.getOrPut(genre) { mutableListOf() }.add(item)
            }
        }
        return buckets.entries
            .sortedByDescending { it.value.size }
            .take(maxGenres)
            .map { GenreGroup(it.key, it.value.take(perGenre)) }
    }

    /** How strongly a title matches a profile's genre affinity: sum of its genres' tallies. */
    private fun genreScore(genre: String?, affinity: Map<String, Int>): Int =
        splitGenres(genre).sumOf { affinity[it] ?: 0 }

    /** How many of a title's genres are in the wanted set — the More-Like-This overlap. */
    private fun sharedGenreCount(genre: String?, wanted: Set<String>): Int =
        splitGenres(genre).count { it in wanted }

    /** Extracts a movie's local id from a playback-position mediaKey ("movie:42" → 42). */
    private fun movieIdFromMediaKey(mediaKey: String): Long? {
        val parts = mediaKey.split(":", limit = 2)
        return if (parts.size == 2 && parts[0] == "movie") parts[1].toLongOrNull() else null
    }

    /**
     * Recommended row from the profile's most-watched genre, capped. No watch history with a
     * genre leaves the row empty — it does not fall back to a scan of the table.
     */
    suspend fun recommendedMoviesLimited(profileId: Long, limit: Int = 30): List<Movie> =
        withContext(Dispatchers.IO) {
            val ids = watchedMovieIds(profileId)
            if (ids.isEmpty()) return@withContext emptyList()
            val watched = movieDao.byIds(ids.toList())
            val top = topGenreTokens(watched.map { it.genre }, 1).firstOrNull() ?: return@withContext emptyList()
            movieDao.inGenre(top, limit).filter { it.id !in ids }.take(limit)
        }

    /**
     * Genre shelves as bounded queries. At or under [libraryCap] the tokens come from the genre
     * column only. Above it they come from the profile's watched titles and the newest rows.
     */
    suspend fun movieGenreShelves(
        profileId: Long,
        libraryCap: Int,
        maxGenres: Int = 12,
        perGenre: Int = 30,
    ): List<GenreGroup<Movie>> = withContext(Dispatchers.IO) {
        val count = movieDao.count()
        if (count == 0) return@withContext emptyList()
        val tokens = if (count <= libraryCap) {
            topGenreTokens(movieDao.genreValues(), maxGenres)
        } else {
            val ids = watchedMovieIds(profileId)
            val watched = if (ids.isEmpty()) emptyList() else movieDao.byIds(ids.toList())
            val recent = movieDao.recentlyAddedList(perGenre)
            topGenreTokens(watched.map { it.genre } + recent.map { it.genre }, maxGenres)
        }
        tokens.map { GenreGroup(it, movieDao.inGenre(it, perGenre)) }
    }

    /** Series sibling of [movieGenreShelves]. Episodes are not a genre history, so a large
     *  library takes its tokens from the newest rows only. */
    suspend fun seriesGenreShelves(
        libraryCap: Int,
        maxGenres: Int = 12,
        perGenre: Int = 30,
    ): List<GenreGroup<Series>> = withContext(Dispatchers.IO) {
        val count = seriesDao.count()
        if (count == 0) return@withContext emptyList()
        val tokens = if (count <= libraryCap) {
            topGenreTokens(seriesDao.genreValues(), maxGenres)
        } else {
            topGenreTokens(seriesDao.recentlyAddedList(perGenre).map { it.genre }, maxGenres)
        }
        tokens.map { GenreGroup(it, seriesDao.inGenre(it, perGenre)) }
    }

    private fun topGenreTokens(raws: List<String?>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val counts = HashMap<String, Int>()
        for (raw in raws) {
            for (token in splitGenres(raw)) counts[token] = (counts[token] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    suspend fun setChannelFavourite(id: Long, favourite: Boolean) =
        channelDao.setFavourite(id, favourite)

    suspend fun setChannelHidden(id: Long, hidden: Boolean) = channelDao.setHidden(id, hidden)

    /** Sets (or clears, on blank) a channel's manual rename. Trimmed; a blank name clears it. */
    suspend fun setChannelCustomName(id: Long, name: String?) =
        channelDao.setCustomName(id, name?.trim()?.takeIf { it.isNotBlank() })

    suspend fun setChannelSortIndex(id: Long, sortIndex: Int) = channelDao.setSortIndex(id, sortIndex)

    // --- Web channel manager: one-shot reads for the socket-thread server ------------------------
    // The manager server handles one HTTP request at a time off the UI thread and wants plain
    // lists, not Flows. These are read-only pass-throughs; every mutation still goes through the
    // suspend setters above (setChannelHidden/Favourite/CustomName/SortIndex).

    /** Enabled providers, for the manager's source selector. */
    suspend fun enabledSources(): List<Source> = sourceDao.enabled()

    /** Every live category (raw, un-folded), for the manager's category list. */
    suspend fun liveCategories(): List<Category> = categoryDao.allByKind(StreamKind.LIVE)

    /** Channel counts per (source, category) — the number shown next to each category. */
    suspend fun categoryChannelCounts(): List<app.opentv.data.db.CategoryChannelCount> =
        channelDao.channelCountsByCategory()

    /** One category's channels, hidden included, optionally scoped to a single source. */
    suspend fun channelsInCategoryForManager(sourceId: Long?, categoryId: String): List<Channel> =
        channelDao.channelsInCategoryIncludingHidden(sourceId, categoryId)

    suspend fun setMovieFavourite(id: Long, favourite: Boolean) =
        movieDao.setFavourite(id, favourite)

    // --- Sync helpers: read/apply curation by stable stream URL ---
    suspend fun favouriteChannelUrls(): List<String> = channelDao.favouriteUrls()
    suspend fun hiddenChannelUrls(): List<String> = channelDao.hiddenUrls()
    suspend fun markChannelFavouriteByUrl(url: String) = channelDao.markFavouriteByUrl(url)
    suspend fun markChannelHiddenByUrl(url: String) = channelDao.markHiddenByUrl(url)
    suspend fun favouriteMovieUrls(): List<String> = movieDao.favouriteUrls()
    suspend fun markMovieFavouriteByUrl(url: String) = movieDao.markFavouriteByUrl(url)
    suspend fun favouriteSeriesIds(): List<String> = seriesDao.favouriteSeriesIds()
    suspend fun markSeriesFavouriteBySeriesId(seriesId: String) =
        seriesDao.markFavouriteBySeriesId(seriesId)

    /** Series episodes are fetched lazily — panels are slow and most series are never opened. */
    suspend fun ensureEpisodes(source: Source, seriesId: String) {
        if (source.kind != SourceKind.XTREAM) return
        runCatching { api.episodes(source, seriesId) }
            .onSuccess { if (it.isNotEmpty()) episodeDao.upsertAll(it) }
            .onFailure { Log.w(TAG, "Episode fetch failed for series $seriesId", it) }
    }

    /**
     * Full catalogue: live channels, then movies and series. Used by the periodic worker and by a
     * manual refresh. [meter] carries the shared percent across every step when the UI is watching.
     */
    suspend fun sync(source: Source, nowUtcMillis: Long, meter: LoadMeter? = null): SyncResult =
        withContext(Dispatchers.IO) {
            val live =
                if (settings.liveEnabled.value) syncLive(source, nowUtcMillis, meter)
                else SyncResult.Success(0, 0, 0)
            if (live is SyncResult.Success && source.kind == SourceKind.XTREAM) {
                runCatching { syncXtreamVod(source, nowUtcMillis, meter) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        LoadTrace.mark("vod sync failed id=${source.id}", error)
                        Log.w(TAG, "VOD sync failed for source ${source.id}", error)
                    }
            }
            live
        }

    /**
     * Weighs the downloads this source will actually make, so the percent has a denominator
     * before the first byte arrives. A missing length falls back to the size remembered from
     * the previous sync. It does not invent one.
     */
    suspend fun planLoad(
        source: Source,
        meter: LoadMeter,
        includeLive: Boolean,
        includeVod: Boolean,
    ) {
        coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
            if (includeLive) {
                jobs += async { meter.plan(LoadStep.CHANNELS, probeStep(source, LoadStep.CHANNELS)) }
            }
            if (includeVod && source.kind == SourceKind.XTREAM && settings.moviesEnabled.value) {
                jobs += async { meter.plan(LoadStep.MOVIES, probeStep(source, LoadStep.MOVIES)) }
            }
            if (includeVod && source.kind == SourceKind.XTREAM && settings.seriesEnabled.value) {
                jobs += async { meter.plan(LoadStep.SERIES, probeStep(source, LoadStep.SERIES)) }
            }
            jobs.awaitAll()
        }
    }

    /** Live channels only — the fast path so the guide can show before VOD and the guide load. */
    suspend fun syncLive(
        source: Source,
        nowUtcMillis: Long,
        meter: LoadMeter? = null,
    ): SyncResult = withContext(Dispatchers.IO) {
        LoadTrace.mark("syncLive start id=${source.id} kind=${source.kind}")
        try {
            val result = when (source.kind) {
                SourceKind.XTREAM -> syncXtreamLive(source, nowUtcMillis, meter)
                SourceKind.M3U -> syncM3u(source, nowUtcMillis, meter)
                SourceKind.STALKER -> syncStalkerLive(source, nowUtcMillis)
            }
            val detail = when (result) {
                is SyncResult.Success -> "ok channels=${result.channelCount}"
                is SyncResult.Failed -> "failed ${result.reason}"
            }
            LoadTrace.mark("syncLive end id=${source.id} $detail")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LoadTrace.mark("syncLive failed id=${source.id} kind=${source.kind}", t)
            Log.w(TAG, "Catalogue sync failed for source ${source.id}", t)
            val oom = t is OutOfMemoryError
            val partial = t is java.io.InterruptedIOException
            SyncResult.Failed(
                when {
                    oom -> TOO_LARGE
                    partial -> PARTIAL
                    else -> t.message ?: "The catalogue could not be downloaded."
                },
                t,
                tooLarge = oom,
                partial = partial,
            )
        }
    }

    /**
     * Movies + series. Returns false when a step failed so the freshness stamp is not set and
     * the next open tries again. An account with no VOD is a success, not a failure.
     */
    suspend fun syncVod(source: Source, nowUtcMillis: Long, meter: LoadMeter? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (source.kind != SourceKind.XTREAM) return@withContext true
            try {
                syncXtreamVod(source, nowUtcMillis, meter)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LoadTrace.mark("vod sync failed id=${source.id}", t)
                Log.w(TAG, "VOD sync failed for source ${source.id}", t)
                false
            }
        }

    private suspend fun syncXtreamLive(
        source: Source,
        nowUtcMillis: Long,
        meter: LoadMeter?,
    ): SyncResult {
        // Authenticate first so a wrong password produces a clear message rather than
        // four separate confusing failures further down.
        api.authenticate(source)

        val liveCategories = api.liveCategories(source)
        if (liveCategories.isNotEmpty()) categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        val accepted = writeXtreamChannels(source, categoryNames, nowUtcMillis, meter)
        if (accepted == 0) {
            return SyncResult.Failed(
                "The server returned no channels. The account may have no package assigned.",
                null,
            )
        }
        channelDao.deleteStale(source.id, nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(accepted, 0, 0)
    }

    /**
     * Stalker/Ministra live sync. Mirrors [syncXtreamLive] but the channels it stores carry a
     * [Channel.cmd] instead of a directly-playable URL — [resolvePlaybackUrl] mints the real URL at
     * play time. The handshake happens inside [StalkerApi] on first call; a bad MAC or URL throws
     * here with a clear message, caught by [syncLive].
     */
    private suspend fun syncStalkerLive(source: Source, nowUtcMillis: Long): SyncResult {
        val liveCategories = stalkerApi.liveCategories(source)
        val channels = stalkerApi.liveChannels(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "The portal returned no channels. The MAC may not be authorised, or its package is empty.",
                null,
            )
        }
        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    /**
     * The URL to actually feed the player for [channel]. Xtream/M3U channels already carry a playable
     * [Channel.streamUrl]; a Stalker channel's real URL is short-lived, so it's minted now via
     * create_link from the channel's [Channel.cmd]. Falls back to the stored streamUrl if resolution
     * fails, so the player surfaces an error rather than silently doing nothing.
     */
    suspend fun resolvePlaybackUrl(channel: Channel, source: Source?): String {
        if (source?.kind != SourceKind.STALKER) return channel.streamUrl
        val cmd = channel.cmd?.takeIf { it.isNotBlank() } ?: return channel.streamUrl
        return runCatching { stalkerApi.createLink(source, cmd) }.getOrNull() ?: channel.streamUrl
    }

    private suspend fun syncXtreamVod(source: Source, nowUtcMillis: Long, meter: LoadMeter?) {
        val moviesOn = settings.moviesEnabled.value
        val seriesOn = settings.seriesEnabled.value
        if (!moviesOn && !seriesOn) return

        if (moviesOn) {
            val movieCategories =
                runCatching { api.movieCategories(source) }.getOrDefault(emptyList())
            if (movieCategories.isNotEmpty()) categoryDao.upsertAll(movieCategories)
            val names = movieCategories.associate { it.id to it.name }
            writeMovies(source, nowUtcMillis, names, meter)
            movieDao.deleteStale(source.id, nowUtcMillis)
        }
        if (seriesOn) {
            val seriesCategories =
                runCatching { api.seriesCategories(source) }.getOrDefault(emptyList())
            if (seriesCategories.isNotEmpty()) categoryDao.upsertAll(seriesCategories)
            val names = seriesCategories.associate { it.id to it.name }
            writeSeries(source, nowUtcMillis, names, meter)
            seriesDao.deleteStale(source.id, nowUtcMillis)
        }
    }

    private suspend fun syncM3u(
        source: Source,
        nowUtcMillis: Long,
        meter: LoadMeter?,
    ): SyncResult {
        LoadTrace.mark("m3u download start id=${source.id}")
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", source.userAgent)
            .build()
        val call = downloadHttp.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        var accepted = 0
        val batch = ArrayList<Channel>(CATALOGUE_BATCH)
        val categoryNames = ArrayList<String>()
        val seenCategories = HashSet<String>()
        var categoriesWritten = 0
        suspend fun flushCategories() {
            if (categoriesWritten >= categoryNames.size) return
            writeM3uCategories(source.id, categoryNames)
            categoriesWritten = categoryNames.size
        }
        fun noteCategory(channel: Channel) {
            val name = channel.categoryId?.takeIf { it.isNotBlank() } ?: return
            if (seenCategories.add(name)) categoryNames += name
        }
        try {
        val header = call.execute().use { response ->
            val length = response.body?.contentLength() ?: -1L
            LoadTrace.mark("m3u response id=${source.id} http=${response.code} bytes=$length")
            if (!response.isSuccessful) {
                return SyncResult.Failed("Playlist download failed (HTTP ${response.code}).", null)
            }
            val stream = response.body?.byteStream()
                ?: return SyncResult.Failed("The playlist was empty.", null)
            meter?.begin(LoadStep.CHANNELS, length)
            val counting = CountingInputStream(stream) { read -> meter?.setBytes(read) }
            LoadTrace.mark("m3u parse start id=${source.id}")
            M3uParser.parse(counting.bufferedReader(), source.id) { channel ->
                currentCoroutineContext().ensureActive()
                noteCategory(channel)
                batch += channel
                accepted++
                if (batch.size >= CATALOGUE_BATCH) {
                    flushChannels(source.id, batch, nowUtcMillis)
                    batch.clear()
                    flushCategories()
                    meter?.setItems(accepted)
                    LoadTrace.mark("m3u batch n=$accepted")
                }
            }
        }
        if (batch.isNotEmpty()) {
            flushChannels(source.id, batch, nowUtcMillis)
            meter?.setItems(accepted)
            LoadTrace.mark("m3u batch n=$accepted")
        }
        flushCategories()
        if (accepted == 0) {
            return SyncResult.Failed(
                "No channels found in that playlist. Check the URL points at an M3U file.",
                null,
            )
        }

        writeM3uCategories(source.id, header.categories)
        LoadTrace.mark(
            "m3u parsed id=${source.id} channels=$accepted skipped=${header.skippedEntries} " +
                "categories=${header.categories.size}",
        )
        remember(source.id, "channels", meter?.endStep(accepted) ?: -1L)

        if (source.epgUrl.isNullOrBlank() && !header.declaredEpgUrl.isNullOrBlank()) {
            sourceDao.update(source.copy(epgUrl = header.declaredEpgUrl))
        }
        channelDao.deleteStale(source.id, nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(accepted, 0, 0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A timeout used to drop every group-title. The guide then had channels on disk,
            // an empty rail, and nothing it would list.
            runCatching { flushCategories() }
            val oom = t is OutOfMemoryError
            val partial = t is java.io.InterruptedIOException
            return SyncResult.Failed(
                when {
                    oom -> TOO_LARGE
                    partial -> PARTIAL
                    else -> t.message ?: "The playlist download failed."
                },
                t,
                tooLarge = oom,
                partial = partial,
            )
        }
    }

    /**
     * Builds or corrects category rows from group titles already stored on channels.
     * Runs even when live categories already exist, so a database imported before movie and
     * series groups were recognised still gets reclassified. Not a `SELECT *` of the channels.
     */
    suspend fun repairLiveCategories(sourceId: Long) {
        val seeds = channelDao.liveCategorySeeds(sourceId)
        if (seeds.isEmpty()) return
        val existing = categoryDao.forSource(sourceId).groupBy { it.id }
        var changed = 0
        for (seed in seeds) {
            val rows = existing[seed.categoryId].orEmpty()
            val label = rows.firstOrNull()?.name ?: seed.categoryId
            val kind = M3uGroups.kindOf(label)
            if (kind == null) {
                if (rows.isNotEmpty()) {
                    categoryDao.deleteIds(sourceId, listOf(seed.categoryId))
                    changed++
                }
                continue
            }
            val current = rows.singleOrNull()
            if (current != null && current.kind == kind) continue
            categoryDao.deleteIds(sourceId, listOf(seed.categoryId))
            categoryDao.upsertAll(
                listOf(
                    Category(
                        id = seed.categoryId,
                        sourceId = sourceId,
                        name = label,
                        kind = kind,
                        sortIndex = current?.sortIndex ?: seed.sortIndex,
                    ),
                ),
            )
            changed++
        }
        if (changed > 0) LoadTrace.mark("categories repaired source=$sourceId n=$changed")
    }

    /**
     * Writes M3U group titles with the right kind. A decorated copy is removed, not stored.
     * Replaces any previous row for the same id so a `LIVE` leftover does not sit beside the
     * new kind — the primary key includes `kind`.
     */
    private suspend fun writeM3uCategories(sourceId: Long, names: List<String>) {
        if (names.isEmpty()) return
        val fresh = ArrayList<Category>(names.size)
        val hide = ArrayList<String>()
        for ((index, name) in names.withIndex()) {
            val kind = M3uGroups.kindOf(name)
            if (kind == null) hide += name
            else {
                fresh += Category(
                    id = name,
                    sourceId = sourceId,
                    name = name,
                    kind = kind,
                    sortIndex = index,
                )
            }
        }
        if (hide.isNotEmpty()) categoryDao.deleteIds(sourceId, hide)
        if (fresh.isNotEmpty()) {
            categoryDao.deleteIds(sourceId, fresh.map { it.id })
            categoryDao.upsertAll(fresh)
        }
    }

    /**
     * Stamps every channel with its normalised identity before it is stored.
     *
     * This one pass powers both headline features: [Channel.groupKey] folds quality
     * variants of a channel into a single row and is the join key for EPG matching, and
     * [Channel.displayName] is the cleaned name the UI shows instead of `UK| BBC ONE FHD`.
     */
    private fun normalized(
        channels: List<Channel>,
        categoryNames: Map<String, String>,
    ): List<Channel> = channels.map { normalizeOne(it, categoryNames) }

    private fun normalizeOne(channel: Channel, categoryNames: Map<String, String>): Channel {
        val n = ChannelNameNormalizer.normalize(channel.name)
        var rank = n.qualityRank
        var label = n.qualityLabel
        if (label.isEmpty() || rank == 0) {
            // Some providers put the quality in the CATEGORY, not the channel:
            // 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc' holding identically named
            // channels. Without this, the player's switch is four buttons all
            // reading 'Standard' — grouped correctly, labelled uselessly.
            val categoryName = channel.categoryId?.let { categoryNames[it] }
            if (categoryName != null) {
                val c = ChannelNameNormalizer.normalize(categoryName)
                if (label.isEmpty()) label = c.qualityLabel
                if (rank == 0) rank = c.qualityRank
            }
        }
        return channel.copy(
            displayName = n.baseName,
            groupKey = n.groupKey,
            qualityRank = rank,
            qualityLabel = label,
            hidden = channel.hidden || isSeparatorRow(channel.name),
        )
    }

    private suspend fun writeXtreamChannels(
        source: Source,
        categoryNames: Map<String, String>,
        nowUtcMillis: Long,
        meter: LoadMeter?,
    ): Int {
        val seen = LongOpenSet()
        val batch = ArrayList<Channel>(CATALOGUE_BATCH)
        var accepted = 0
        api.streamLive(
            source,
            onLength = { length -> meter?.begin(LoadStep.CHANNELS, length) },
            onBytes = { read -> meter?.setBytes(read) },
        ) { channel ->
            currentCoroutineContext().ensureActive()
            if (!seen.add(fnv64(channel.streamId))) return@streamLive
            batch += normalizeOne(channel, categoryNames)
            accepted++
            if (batch.size >= CATALOGUE_BATCH) {
                flushChannels(source.id, batch, nowUtcMillis)
                batch.clear()
                meter?.setItems(accepted)
                LoadTrace.mark("xtream batch n=$accepted")
            }
        }
        if (batch.isNotEmpty()) {
            flushChannels(source.id, batch, nowUtcMillis)
            meter?.setItems(accepted)
            LoadTrace.mark("xtream batch n=$accepted")
        }
        remember(source.id, "channels", meter?.endStep(accepted) ?: -1L)
        return accepted
    }

    private suspend fun writeMovies(
        source: Source,
        syncStamp: Long,
        categoryNames: Map<String, String>,
        meter: LoadMeter?,
    ) {
        val seen = LongOpenSet()
        val batch = ArrayList<Movie>(CATALOGUE_BATCH)
        var accepted = 0
        api.streamMovies(
            source,
            onLength = { length -> meter?.begin(LoadStep.MOVIES, length) },
            onBytes = { read -> meter?.setBytes(read) },
        ) { movie ->
            currentCoroutineContext().ensureActive()
            if (!seen.add(fnv64(movie.streamId))) return@streamMovies
            batch += movie
            accepted++
            if (batch.size >= CATALOGUE_BATCH) {
                flushMovies(source.id, batch, syncStamp, categoryNames)
                batch.clear()
                meter?.setItems(accepted)
                LoadTrace.mark("movies batch n=$accepted")
            }
        }
        if (batch.isNotEmpty()) flushMovies(source.id, batch, syncStamp, categoryNames)
        remember(source.id, "movies", meter?.endStep(accepted) ?: -1L)
    }

    private suspend fun writeSeries(
        source: Source,
        syncStamp: Long,
        categoryNames: Map<String, String>,
        meter: LoadMeter?,
    ) {
        val seen = LongOpenSet()
        val batch = ArrayList<Series>(CATALOGUE_BATCH)
        var accepted = 0
        api.streamSeries(
            source,
            onLength = { length -> meter?.begin(LoadStep.SERIES, length) },
            onBytes = { read -> meter?.setBytes(read) },
        ) { item ->
            currentCoroutineContext().ensureActive()
            if (!seen.add(fnv64(item.seriesId))) return@streamSeries
            batch += item
            accepted++
            if (batch.size >= CATALOGUE_BATCH) {
                flushSeries(source.id, batch, syncStamp, categoryNames)
                batch.clear()
                meter?.setItems(accepted)
                LoadTrace.mark("series batch n=$accepted")
            }
        }
        if (batch.isNotEmpty()) flushSeries(source.id, batch, syncStamp, categoryNames)
        remember(source.id, "series", meter?.endStep(accepted) ?: -1L)
    }

    private suspend fun flushChannels(sourceId: Long, batch: List<Channel>, syncStamp: Long) {
        if (batch.isEmpty()) return
        val state = channelDao.userStateForStreamIds(sourceId, batch.map { it.streamId })
            .associateBy { it.streamId }
        val fresh = ArrayList<Channel>()
        val known = ArrayList<Channel>()
        for (channel in batch) {
            val previous = state[channel.streamId]
            if (previous == null) {
                fresh += channel.copy(lastSeenMillis = syncStamp)
            } else {
                known += channel.copy(
                    id = previous.id,
                    favourite = previous.favourite,
                    hidden = previous.hidden || channel.hidden,
                    sortIndex = previous.sortIndex,
                    customName = previous.customName,
                    epgOverrideId = previous.epgOverrideId,
                    matchedEpgId = previous.matchedEpgId,
                    lastSeenMillis = syncStamp,
                )
            }
        }
        if (fresh.isNotEmpty()) channelDao.insertIgnore(fresh)
        if (known.isNotEmpty()) channelDao.updateAll(known)
    }

    private suspend fun flushMovies(
        sourceId: Long,
        batch: List<Movie>,
        syncStamp: Long,
        categoryNames: Map<String, String>,
    ) {
        if (batch.isEmpty()) return
        val state = movieDao.userStateForStreamIds(sourceId, batch.map { it.streamId })
            .associateBy { it.streamId }
        val fresh = ArrayList<Movie>()
        val known = ArrayList<Movie>()
        val categories = LinkedHashMap<String, Category>()
        for (movie in batch) {
            val stamped = movie.copy(lastSeenMillis = syncStamp)
            val previous = state[movie.streamId]
            if (previous == null) fresh += stamped
            else known += stamped.copy(id = previous.id, favourite = previous.favourite)
            val categoryId = movie.categoryId?.takeIf { it.isNotBlank() } ?: continue
            if (categoryId !in categories) {
                categories[categoryId] = Category(
                    id = categoryId,
                    sourceId = sourceId,
                    name = categoryNames[categoryId] ?: categoryId,
                    kind = StreamKind.MOVIE,
                    sortIndex = categories.size,
                )
            }
        }
        if (fresh.isNotEmpty()) movieDao.insertIgnore(fresh)
        if (known.isNotEmpty()) movieDao.updateAll(known)
        if (categories.isNotEmpty()) categoryDao.insertIgnore(categories.values.toList())
    }

    private suspend fun flushSeries(
        sourceId: Long,
        batch: List<Series>,
        syncStamp: Long,
        categoryNames: Map<String, String>,
    ) {
        if (batch.isEmpty()) return
        val state = seriesDao.userStateForSeriesIds(sourceId, batch.map { it.seriesId })
            .associateBy { it.seriesId }
        val fresh = ArrayList<Series>()
        val known = ArrayList<Series>()
        val categories = LinkedHashMap<String, Category>()
        for (item in batch) {
            val stamped = item.copy(lastSeenMillis = syncStamp)
            val previous = state[item.seriesId]
            if (previous == null) fresh += stamped
            else known += stamped.copy(id = previous.id, favourite = previous.favourite)
            val categoryId = item.categoryId?.takeIf { it.isNotBlank() } ?: continue
            if (categoryId !in categories) {
                categories[categoryId] = Category(
                    id = categoryId,
                    sourceId = sourceId,
                    name = categoryNames[categoryId] ?: categoryId,
                    kind = StreamKind.SERIES,
                    sortIndex = categories.size,
                )
            }
        }
        if (fresh.isNotEmpty()) seriesDao.insertIgnore(fresh)
        if (known.isNotEmpty()) seriesDao.updateAll(known)
        if (categories.isNotEmpty()) categoryDao.insertIgnore(categories.values.toList())
    }

    private fun probeStep(source: Source, step: LoadStep): Long {
        val key = when (step) {
            LoadStep.CHANNELS -> "channels"
            LoadStep.MOVIES -> "movies"
            LoadStep.SERIES -> "series"
            else -> return -1L
        }
        val url = when (step) {
            LoadStep.CHANNELS -> when (source.kind) {
                SourceKind.M3U -> source.url
                SourceKind.XTREAM -> api.actionUrl(source, "get_live_streams").toString()
                SourceKind.STALKER -> null
            }
            LoadStep.MOVIES -> api.actionUrl(source, "get_vod_streams").toString()
            LoadStep.SERIES -> api.actionUrl(source, "get_series").toString()
            else -> null
        }
        val probed = if (url.isNullOrBlank()) -1L else probeLength(url, source.userAgent)
        return if (probed > 0) probed else settings.loadBytes(source.id, key)
    }

    private fun probeLength(url: String, userAgent: String): Long {
        return try {
            val request = Request.Builder().url(url).head().header("User-Agent", userAgent).build()
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

    private fun remember(sourceId: Long, step: String, bytes: Long) {
        settings.saveLoadBytes(sourceId, step, bytes)
    }

    /**
     * Re-cleans every stored channel with the current normaliser, no network needed.
     *
     * displayName, groupKey, quality and separator-hiding are computed at import time, so a
     * change to the normaliser (a new superscript char, a new junk pattern) does not reach
     * channels already in the database until the next full catalogue sync — which can be
     * hours away. This runs the same pass over existing rows locally, so a code fix shows up
     * on the next launch instead of the next sync. Bump [NORMALIZER_VERSION] to trigger it.
     */
    suspend fun renormalizeAll(): Int = withContext(Dispatchers.IO) {
        var afterId = 0L
        var changed = 0
        while (true) {
            val existing = channelDao.pageAfterId(afterId, MATCH_PAGE)
            if (existing.isEmpty()) break
            afterId = existing.last().id
            val bySource = existing.groupBy { it.sourceId }
            for ((_, channels) in bySource) {
                val names = channelCategoryNames(channels)
                val renamed = normalized(channels, names)
                val diff = renamed.filterIndexed { i, c ->
                    val old = channels[i]
                    c.displayName != old.displayName || c.groupKey != old.groupKey ||
                        c.qualityLabel != old.qualityLabel || c.qualityRank != old.qualityRank ||
                        c.hidden != old.hidden
                }
                if (diff.isNotEmpty()) {
                    channelDao.updateAll(diff)
                    changed += diff.size
                }
            }
        }
        Log.i(TAG, "Re-normalised $changed channels with the current normaliser")
        changed
    }

    /** Best-effort category-id → name map for a set of channels. */
    private suspend fun channelCategoryNames(channels: List<Channel>): Map<String, String> {
        val ids = channels.mapNotNull { it.categoryId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return categoryDao.namesFor(ids).associate { it.id to it.name }
    }

    /**
     * The switchable quality variants of a channel, best first, ONE per distinct quality.
     *
     * A provider often lists the same stream in several categories — PRIME American Crimes
     * shows up twice, both RAW. Those are not "qualities"; offering a switch between two
     * identical (and sometimes one dead) feeds is worse than useless. So variants are
     * de-duplicated by their quality signature: only genuinely different qualities survive,
     * and a channel that is really single-quality gets no switch at all.
     */
    suspend fun variants(channel: Channel): List<Channel> {
        if (channel.groupKey.isEmpty()) return listOf(channel)
        val all = channelDao.variantsInGroup(channel.groupKey)
        return distinctByQuality(all).ifEmpty { listOf(channel) }
    }

    /**
     * The record menu's "record from which provider" options: one channel per source that carries
     * this logical channel, each that source's best-quality copy, the current source first. With two
     * providers this lets a recording run on one account while the user keeps watching on the other —
     * the only real way around a provider's single-connection limit (which even TiviMate can't dodge).
     */
    suspend fun recordSourceOptions(channel: Channel): List<Channel> = withContext(Dispatchers.IO) {
        if (channel.groupKey.isEmpty()) return@withContext listOf(channel)
        channelDao.variantsInGroup(channel.groupKey)
            .groupBy { it.sourceId }
            .map { (_, chans) -> chans.maxByOrNull { it.qualityRank } ?: chans.first() }
            .sortedByDescending { it.sourceId == channel.sourceId }
    }

    /**
     * Switches an Xtream source's live-stream container (HLS ↔ MPEG-TS) and rewrites its live
     * channels' playback URLs in place, so the change takes effect immediately without a re-sync.
     *
     * Xtream only: an M3U source's channel URLs come straight from its playlist and must not be
     * rebuilt from credentials. The `channels` table is live-only (movies and series have their own
     * tables), so every row here is a live channel whose URL derives from [XtreamApi.liveStreamUrl].
     */
    suspend fun setLiveFormat(sourceId: Long, format: LiveStreamFormat) = withContext(Dispatchers.IO) {
        val source = sourceDao.byId(sourceId) ?: return@withContext
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return@withContext
        val updated = source.copy(liveFormat = format)
        sourceDao.update(updated)
        var afterId = 0L
        while (true) {
            val page = channelDao.pageForSource(sourceId, afterId, MATCH_PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (channel in page) {
                val newUrl = api.liveStreamUrl(updated, channel.streamId)
                if (newUrl != channel.streamUrl) channelDao.updateStreamUrl(channel.id, newUrl)
            }
        }
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        channelDao.deleteForSource(sourceId)
        categoryDao.deleteForSource(sourceId)
        movieDao.deleteForSource(sourceId)
        seriesDao.deleteForSource(sourceId)
        episodeDao.deleteForSource(sourceId)
        sourceDao.delete(sourceId)
    }

    companion object {
        private const val TAG = "CatalogRepository"

        /** Rows per catalogue write. Under SQLite's 999-variable cap on the state lookup. */
        const val CATALOGUE_BATCH = 300

        /** Rows per id-cursor walk of the channel table. */
        private const val MATCH_PAGE = 500

        const val TOO_LARGE =
            "This list is too large to finish. What was already saved is kept. Try again."

        const val PARTIAL =
            "The download stopped before the end. What was already saved is kept."

        /**
         * Bump this whenever the normaliser changes in a way that should re-process
         * already-imported channels. The app compares it against a stored value on launch
         * and runs [renormalizeAll] once when it moves.
         */
        const val NORMALIZER_VERSION = 2
    }
}
