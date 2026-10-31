# What is already in the app

The old gap list treated catch-up, recording, reminders, a settings hub and device sync as
future work. They are in the tree. Read this before opening a change that "adds" one of them.
Behaviour that is easy to break is in [ARCHITECTURE.md](ARCHITECTURE.md), not here.

## Shipped

- Live TV from Xtream, M3U and Stalker, including quality grouping, favourites, hide and reorder
- XMLTV guide, name matching, incremental refresh that does not wipe a failed sync
- Xtream catch-up from `tv_archive` (`XtreamApi.catchupUrl`, started from the guide)
- Recording to the device, USB or SMB, including series link and in-app playback
- Programme reminders, parental PIN, profiles
- Movies and series, resume, and M3U shelves that stay in the `channels` table
- Picture-in-picture, aspect ratio, audio and subtitle pickers, sleep timer
- External player hand-off, last-channel resume
- Opt-in live pause and rewind through a short on-disk buffer
- On-device phone pairing and channel manager
- Sync of favourites, watch history and NAS recordings over the LAN or the user's NAS
- Stremio add-on streams for a movie, behind the user's own TMDB key
- In-app update from GitHub releases
- English tabs Channels, Movies, Series. Brazilian Portuguese is `values-pt-rBR`. European
  Portuguese stays in `values-pt`

## Not in the code

- Kodi catch-up tags on an M3U (`catchup`, `catchup-source`, `append`, `flussonic`). Xtream
  archive playback does not cover those playlists.
- Stalker catalogues of the same size as a large M3U. `syncStalkerLive` still loads the portal
  into one list and calls `replaceCatalogue`.
- A multi-channel grid. Picture-in-picture is one extra window, not N-up.
