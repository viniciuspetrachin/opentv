---
name: large-catalogues
description: >-
  Keep OpenTV catalogues inside device RAM: streaming M3U parse, adaptive
  catalogue batches, paging at 2000 rows, M3U group kinds, no largeHeap, no
  full-table SELECT. Use when editing M3uParser, M3uGroups, CatalogRepository, DAOs, the
  guide, Movies, Series, the channel manager, Xtream, Stalker, or EPG matching.
---

# Large catalogues

A few hundred channels and a few hundred thousand share one pipeline. Do not add a "large
playlist mode".

Confirmed limits, from the code:

| Constant | Value | Where |
| --- | --- | --- |
| `CatalogueBatchPacer.START` | 300 | grows or shrinks from here |
| `CatalogueBatchPacer.FLOOR` | 40 | smallest write after a bad batch |
| `GUIDE_LIST_CAP` | 2000 | `ChannelsViewModel` |
| EPG match page | 500 | `EpgRepository.MATCH_PAGE` |
| EPG programme batch | 500 | `EpgRepository.BATCH_SIZE` |

`MemoryGovernor` never enables `largeHeap`.

## Import

- Stream the body. No `body.string()`, no `parse(text)` on the sync path. `parse(text)` exists
  for tests.
- `M3uParser` emits one channel through a callback. Attributes use `indexOf`. Duplicates use
  `LongOpenSet` of FNV-1a hashes.
- Row id is `xui:<xui-id>` or `url:<fnv of the URL>`. `tvg-id` is the guide id, not the row id.
- Write a batch, drop it, then the next. `CatalogueBatchPacer` picks the size from the last
  write's time and from free heap. It starts at 300 and has no maximum: it keeps growing while
  the write stays fast. Do not put a fixed 300 cap or a fixed ceiling back.
  `streamId IN (...)` stays chunked under 999 variables. `deleteStale` runs once, after the last
  batch succeeds. A failure keeps old rows and whatever was already saved.
- An `OutOfMemoryError` on a write clears images, shrinks the batch, and retries the rows not
  saved yet. At the floor it becomes `SyncResult.Failed`.
- Catch `OutOfMemoryError` inside the sync and return `SyncResult.Failed`. Do not rethrow it
  onto the main thread.
- Xtream live, movies and series already batch this way. Stalker live does not: it still calls
  `replaceCatalogue` with the full list. Do not copy that pattern back onto M3U. If you touch
  Stalker scale, give it the same batch path.
- Log `OpenTV.Load` per batch. Never log the playlist URL.

## M3U groups

`M3uGroups.kindOf` files the category. The channel stays in `channels`.

- `FILMES` / `FILME` → `MOVIE`. `SERIES` / `SÉRIES` / `SERIE` → `SERIES`. Else `LIVE`.
- `24H`, `24 H`, `24 HORAS`, and a film name that also mentions series (`Filmes e Séries`) stay
  `LIVE`.
- A leading diamond (`U+2666` or `U+25C6`) returns null. Do not store that category.
- `repairLiveCategories` runs on guide start even when live categories already exist. The
  category key includes `kind`: delete the old row, then insert. A second upsert leaves both.

Movies and Series, when the category has channel rows, page those channels and play them in the
live player. They do not copy rows into `movies` or `series`. An Xtream category with no channel
rows pages `movies` / `series` by `(addedMillis, id)` descending and opens the detail page.

The Series tab, when `series` is empty, counts one category. It does not count every series
category together. `loadHomeFeeds` uses limited genre queries, not `allMovies()` / `allSeries()`.

## On screen

Above `GUIDE_LIST_CAP`, do not subscribe to All. Page with a cursor, not `OFFSET`.

- Channels: `(sortIndex, id)`, first page `afterSort = Int.MIN_VALUE`, `afterId = -1`.
- Movies and series rows: `(addedMillis, id)` descending.
- The channel manager uses the channel cursor and includes hidden rows.
- Search stays on its own limited query. Do not feed it from the guide's accumulated list.
- Empty, after sync has stopped and the page has returned, is the empty state. A spinner is for
  the first page or a sync still running.

The matcher and the regional-feed scan use `pageAfterId`. Do not bring back `SELECT * FROM channels`.
