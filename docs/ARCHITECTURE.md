# Architecture

This document explains the decisions that are not obvious from reading the code, and in
particular the ones made in direct response to how apps in this category typically fail.

## The shape of it

```
app/src/main/java/app/opentv/
├── core/           ServiceLocator, settings, memory, load tracing
├── data/
│   ├── model/      Domain models, which double as Room entities
│   ├── db/         Room database and DAOs
│   ├── remote/     Xtream, Stalker, Stremio, TMDB, GitHub releases
│   ├── parser/     M3uParser, XmltvParser, M3uGroups
│   ├── repo/       Catalogue, EPG, sources, recordings, reminders
│   └── work/       SyncWorker — periodic background refresh
├── player/         PlayerController, errors, SMB and growing recordings
├── recording/      DVR engine, scheduler, local and SMB storage
├── reminders/      Programme alarms
├── pairing/        On-device pages for phone setup and the channel manager
├── sync/           LAN and NAS bundles. No project server
├── update/         GitHub release check and APK install
└── ui/             Compose screens and view models
```

Single module, single Gradle build. A multi-module setup would build faster in CI and is what
a team of ten would do. This is not a team of ten; it is a project whose survival depends on a
stranger with a broken channel being able to find the relevant code in under five minutes.

## No project server.

OpenTV has no server of its own. There is no account and no login to the project.

Two things that look like a backend are not one. The television runs a small HTTP server on the
LAN so a phone can type a provider login or edit the channel list. Sync exchanges a bundle with
another device on that LAN, or writes a JSON file into a folder on a NAS the user already
configured. Provider credentials are not in the bundle (`sync/SyncData.kt`).

This is the single most important architectural decision in the project, and it is worth being
explicit about why, because a hosted dashboard is a genuinely nice feature and users ask for it.

**A cloud dashboard has to be paid for every month, forever, per user.** An app funded by a
one-off payment or by donations cannot carry that. Every player that has tried has arrived at
the same place: the hosting bill grows with success, the revenue does not, and eventually the
servers go quiet — taking the app with them, because by then the app cannot start without
them. Users who paid discover that their "lifetime" purchase bought them a client for a
service that no longer exists.

So OpenTV stores everything on the device, in SQLite. The consequences are accepted honestly:

- A second TV still needs the provider login typed again. Credentials are the one thing that
  does not sync.
- Favourites, hidden channels, resume positions and NAS recordings can follow you, but only
  through a device or a NAS you control. If that copy is gone, the project has nothing to restore.

And what is bought with it:

- The app costs nothing to run, so it cannot be switched off.
- Provider credentials never leave the device. Nobody has to trust the maintainers with them.
- It works with no internet connection to anything except your own provider.

Sync between devices, when the user turns it on, goes through the LAN or through storage the
user already owns. Never a service the project has to fund.

## The EPG, and why it is written the way it is

A guide that quietly stops working is the defining bug of this app category. It is almost
always caused by the same implementation:

```
delete everything for this source
download the new guide
insert it
```

That is fine when it works. When the download stalls, the provider rate-limits, the box sleeps
mid-sync, or the XML is truncated, the user is left with an empty guide — and the only advice
anyone can give is "reinstall the app", which appears to help because it restarts the sync from
scratch. It fixes nothing. The next refresh fails the same way.

`EpgRepository` never deletes before it has the replacement:

1. Programmes are **upserted in batches of 500** as they stream out of the parser. The unique
   index on `(sourceId, epgChannelId, startUtcMillis)` makes this idempotent, so a sync that
   dies at 60% leaves 60% of a *fresher* guide — strictly better than what was there.
2. Pruning happens **by age** (`deleteEndedBefore`), never by source, and only **after** a sync
   reports success.
3. A failed sync leaves the previous guide completely intact and returns the reason.

`XmltvParser` streams via `XmlPullParser` and emits programmes to a callback. A week of guide
data for a large provider is comfortably 150 MB of XML; nothing about that fits in the heap of
a cheap TV stick, and `String`-based parsing is an OOM waiting to happen.

**Times are stored as UTC epoch millis, always.** `XmltvParser.parseXmltvTime` normalises the
`+0100`-style offsets that XMLTV uses, treats a missing offset as UTC rather than guessing the
device zone, and converts to local time only at draw time. `XmltvTimeTest` changes the JVM's
default zone between assertions specifically to catch the "works on my machine, an hour out on
yours" bug.

## Catalogue refresh preserves what the user did

`ChannelDao.replaceCatalogue` merges rather than wipes: it reads existing rows, carries
`favourite`, `hidden` and `sortIndex` across to the incoming rows by `streamId`, upserts, and
only then deletes channels the provider no longer lists.

This is why `M3uParser` produces a **stable** `streamId`. A row with `xui-id` is `xui:<id>`.
Otherwise the id is `url:` plus an FNV-1a hash of the stream URL, not `String.hashCode()` and
not `tvg-id`. Affiliates often share a `tvg-id`; using it as the row identity would collapse
them. An unstable id means every refresh looks like a completely new set of channels, and the
user's favourites silently vanish.

## SQLite's bound-variable limit is a real constraint here

Two queries are written in a non-obvious way because of it, and both are marked in the code.

SQLite caps the number of bound variables in a statement — 999 on older Android versions,
32766 on newer. A real IPTV catalogue has thousands of channels and a large playlist has tens
of thousands. So neither of these works, despite being the natural thing to write:

- `DELETE FROM channels WHERE streamId NOT IN (:everyCurrentId)` — replaced by a sync-stamp
  comparison (`Channel.lastSeenMillis`), which has no limit.
- `SELECT * FROM programmes WHERE epgChannelId IN (:everyChannelId)` — replaced by a
  time-window query with grouping done in memory by the caller.

Both would pass every test written against a small synthetic dataset and fail on exactly the
providers where it matters most.

## Playback

`PlayerController` owns exactly one `ExoPlayer` for the lifetime of the screen and only ever
swaps its media item.

The naive implementation of "change channel" releases the player and builds a new one. On a
low-end box that is slow enough that a user holding channel-up queues several constructions and
teardowns, and the codec ends up in a state where nothing plays until the app is force-stopped.
That is the entire mechanism behind "changing channels too quickly causes streams to fail", and
it is self-inflicted.

So: requests are debounced (350 ms), an in-flight switch is cancelled the moment a newer one
arrives, and the player is `stop()`ed rather than released. Holding channel-up costs one actual
tune — the one you stopped on.

### Retries are tuned for IPTV, not for CDNs

ExoPlayer's default policy gives up almost immediately on a 403 or a 5xx. That is right for a
CDN and wrong here: a provider under load returns 403, 429 or 503 for a few seconds and then
serves the stream perfectly. Users experience the default as "this channel doesn't work" when
it demonstrably works on the second attempt.

`PlayerController` retries those with exponential backoff (500 ms doubling to 8 s, 5 attempts)
plus up to three silent restarts. `401`, `404` and `410` are **not** retried — those will not
fix themselves.

### Errors say what to do

`PlaybackErrors` turns exceptions into sentences. `ERROR_CODE_IO_BAD_HTTP_STATUS` tells a user
nothing; "the account may already be streaming on another device, or the provider may be
blocking this app's User-Agent — you can change it in the source's advanced settings" tells them
what to try.

This matters more than it sounds. The alternative habit — hiding the error code because users
complained about seeing it — treats the symptom while the stream still fails, and removes the
only diagnostic the user had.

## Defensive parsing of Xtream responses

`XtreamApi` reads `JsonElement` by hand instead of deserialising into `@Serializable` data
classes, and every accessor returns null rather than throwing.

Xtream Codes is not a standard. Different panel builds return the same field as a number, a
quoted number, an empty string, `null`, or omit it. `stream_id` alone flips between `12345` and
`"12345"`. Strict deserialisation means one unusual field aborts the parse of the entire
catalogue, which the user experiences as "the app won't load my channels" with nothing to go on.

It is ugly. It is correct. Do not "clean it up" into typed models without a very large corpus
of real panel responses to test against.

Similarly, `syncXtream` treats VOD as optional: plenty of accounts are live-TV only, and a 404
on `get_vod_streams` must never cost the user their channel list.

## Dependency injection

`ServiceLocator` is a hand-rolled container. Hilt is the conventional choice and is
deliberately not used: it adds an annotation processor, a build-time graph and a class of error
message that is genuinely hard to read, in exchange for wiring about a dozen objects. For a
project hoping for drive-by contributions from people fixing their own bugs, "you can read the
whole graph in one file" is worth more than the ceremony.

## Large catalogues

A playlist of a few hundred thousand lines does not fit in the heap of a cheap stick, and a
`LazyColumn` does not help if the `List` behind it is already the whole table. `MemoryGovernor`
never turns on `largeHeap`. The ceiling is the same `ChannelsViewModel.GUIDE_LIST_CAP` (2 000
rows) for the guide, the channel manager and the movie and series shelves.

### Import

`M3uParser.parse` reads a `BufferedReader` and calls back one channel at a time. Attributes are
taken with `indexOf`, not a `Regex` per line. Duplicate stream ids are filtered with a
`LongOpenSet` of 64-bit hashes. `CatalogRepository.syncM3u` writes batches of
`CATALOGUE_BATCH` (300, under SQLite's old 999-variable cap) and calls `deleteStale` only after
the last batch succeeds. `OutOfMemoryError` becomes `SyncResult.Failed`. It is not rethrown onto
the main thread, and rows already written stay.

Xtream live, movies and series use the same batch size and the same stale-delete-after-success
rule. Stalker live still builds the full list and calls `replaceCatalogue`. A portal the size of
these M3U files would not survive that path.

Progress is `OpenTV.Load` and `files/load-trace.log`. Do not log the playlist URL. The user and
password are on the query string.

### Where an M3U group is filed

The channel row stays in `channels`. Only `categories.kind` changes, in `M3uGroups.kindOf`:

- A name that starts with `FILMES` or `FILME` is `MOVIE`. `SERIES`, `SÉRIES` or `SERIE` is
  `SERIES`. Everything else is `LIVE`.
- A name that contains `24H`, `24 H` or `24 HORAS`, and a name that starts as films and also
  mentions series (`Filmes e Séries`), stays `LIVE`. Those are television channels.
- A leading `♦️` (`U+2666` or `U+25C6`) is a decorated copy of another shelf. `kindOf` returns
  null and the category is not stored.

`repairLiveCategories` runs when the guide view model starts, including when live categories
already exist, so an older database is reclassified without a full re-download. The category
primary key includes `kind`, so a correction deletes the old row and inserts the new one.

Movies and Series read those channels when the category has channel rows. The poster is the
channel name and logo. The click opens the live player, not the Xtream detail page. An Xtream
category, whose rows are in `movies` or `series`, pages by `(addedMillis, id)` and still opens
the detail page. Episodes are fetched when a series is opened, not during catalogue sync.

The Series tab must not `COUNT` every series category at once when the `series` table is empty.
That count is what used to fail and leave the tab blank. One category is enough to decide the
shelf is there. `loadHomeFeeds` uses bounded genre queries. It does not `SELECT` the library.

Above the cap, the guide does not subscribe to All. It selects the first category once. A
category over the cap is paged with the cursor `(sortIndex, id)`, first page `afterSort =
Int.MIN_VALUE` and `afterId = -1`. The channel manager uses the same cursor and includes hidden
rows. The EPG matcher walks `pageAfterId` in pages of 500.

## Testing

The parsers are pure functions over strings and streams, which makes them cheap to test
exhaustively — and they are where the bugs that ruin someone's evening actually live. Every
malformed case in `M3uParserTest` is something that should cost the user one channel, never the
whole playlist.

Things worth testing that are not yet covered, and would be very welcome contributions:

- `EpgRepository.sync` against a mid-stream failure, asserting the old guide survives
- `ChannelDao.replaceCatalogue` preserving favourites across a refresh
- `XtreamApi` against captured real-world panel responses (with credentials stripped)
