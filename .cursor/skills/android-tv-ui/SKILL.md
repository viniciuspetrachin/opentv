---
name: android-tv-ui
description: >-
  Change OpenTV's Jetpack Compose UI for a D-pad first, with one APK for Android
  TV, Fire TV, phones and tablets. Use when editing screens, navigation, player
  controls, settings, or strings and translations.
---

# Android TV UI

One APK. The remote is the primary input. Touch has to work on the same screens. Do not add a
separate phone UI.

## Screens

Compose UI lives under `app/src/main/java/app/opentv/ui/`. View models are in
`OpenTvViewModels.kt`. Navigation starts at `MainActivity` and `MainScreen`.

- Guide, search and the channel manager: `ui/channels/`.
- Movies, series, detail and the VOD player: `ui/vod/`.
- Live player: `ui/player/`. Transport, audio, subtitles, aspect and sleep timer already exist
  on `PlayerScreen`. Extend that bar. Do not add a second player chrome.
- Settings hub and the screens it opens: `ui/settings/`. Add a setting as a hub entry, not a new
  top-level destination.
- Playback policy (one ExoPlayer, debounce, which HTTP statuses retry) is `player/PlayerController`
  and `player/PlaybackErrors`. Read both before changing how a tune fails.

A shelf that can hold more than `GUIDE_LIST_CAP` rows follows the large-catalogues skill. Do not
collect the category into one `StateFlow` list past that cap.

## Copy

Identifiers stay stable (`nav_shows`, `Tab.SHOWS`, `StreamKind.SERIES`). Change the string the
user reads.

- Source of truth: `app/src/main/res/values/strings.xml`. English tabs are Channels, Movies, Series.
- Brazilian Portuguese: `values-pt-rBR`. Tabs are Canais, Filmes, Séries. Vocabulary is você,
  tela, baixar, Configurações, aplicativo, usuário, arquivo. Do not write European Portuguese
  into this folder.
- European Portuguese: `values-pt`. Leave it on its own wording (TV em direto, definições,
  descarregar). A pt-BR device must not fall through to it for strings that exist in
  `values-pt-rBR`.
- Other locales: do not mass-edit them as part of an English or Portuguese change.
- Keep placeholders (`%1$s`, `%1$d`) in the same order. Escape quotes and ampersands the way the
  English file already does. No new string without an English key.

## Check

After a UI change, exercise the screen on a TV layout and a phone layout: D-pad move, select,
back, and the empty state. Strings: confirm the key you changed resolves in `values`,
`values-pt-rBR` and `values-pt` when that key already exists in all three.
