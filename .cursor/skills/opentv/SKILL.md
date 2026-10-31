---
name: opentv
description: >-
  Apply OpenTV invariants when changing this Android IPTV app: clean-room code,
  no project-operated server, ServiceLocator instead of Hilt, credentials stay
  on the device, GPL-3.0-or-later. Use when editing OpenTV, planning a feature,
  reviewing a change, or when the user asks to improve the app.
---

# OpenTV

The app is the product the maintainer wants. Improve it in place. Do not redesign it.

## Before changing behaviour

Read the file you are about to edit, then `docs/ARCHITECTURE.md` for the decision behind it.
`docs/ROADMAP.md` lists what is already shipped. Do not reimplement a feature that is already
in the tree.

## Hard rules

- Do not decompile another IPTV app or paste code from one. Public specs and observed behaviour
  are fine. See CONTRIBUTING.md.
- Do not add a server, account, or backend the project would have to host. Phone pairing and
  sync already run on the device or on storage the user owns.
- Do not put provider URL, username or password in logs, issues, or sync bundles.
- Keep dependency wiring in `core/ServiceLocator.kt`. Do not introduce Hilt.
- One module. A stranger should find the relevant code in one place.
- New user-visible copy goes in `values/strings.xml` first. See the android-tv-ui skill when
  editing screens or translations.
- Catalogue size is a separate skill (`large-catalogues`). A change that loads a table into a
  list is a bug even when the sample playlist is small.

## Checks

```bash
./gradlew test
```

JDK 17 and Android SDK API 35. Debug APK: `app/build/outputs/apk/debug/`.

Device logs: `adb logcat -s OpenTV.Load:I AndroidRuntime:E`. The on-device file is
`files/load-trace.log`.
