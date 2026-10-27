# Changelog

All notable changes to this custom build of Retro Music Player are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

All custom releases keep upstream's version name **7.0.3** and are built from upstream `dev` at
`a7d4d05` (6 commits after `v7.0.3`). They're distinguished by their versionCode and the
`-custom.N` name. Only custom.3 was published as a GitHub release; custom.1 and custom.2 were
shared as APK files. For changes in the app itself, see the
[upstream project](https://github.com/RetroMusicPlayer/RetroMusicPlayer).

## [Unreleased]

### Added
- "Resume after other media" setting in Audio, under "Always play" (off by default). When it's on
  and Always play is off, Retro pauses when another app starts playing media and resumes about a
  second after that media stops. Short gaps, such as between two videos, don't trigger a resume.
  A manual play, pause or stop cancels the automatic resume. versionCode 10709.

### Fixed
- Resume after other media never resumed (seen on Android 17): paused players still counted as
  playing. It now checks whether media audio is actually playing, and retries if Android refuses
  audio focus.
- A paused browser video still counted as playing, because browsers keep their audio stream open.
  New optional "Detect paused videos" setting (notification access) reads other apps' play/pause
  state instead. Retro also no longer stops its service while a resume is pending.
- "Resume after a set time" switch with a 0.5-20 s delay slider (default 3 s): resumes after the
  delay even if other media still seems to be playing.
- The notification-access listener now unbinds itself as soon as it connects and blocks every
  notification-reading method, so it can't read notifications.
- Temporary diagnostic: "Resume log" switch (off by default; turning it off deletes the log) and
  "Copy resume log" in Audio settings, which copies the last 200 resume events.
- Documentation for the custom build: README, this changelog, `docs/BUILDING.md`,
  `docs/CUSTOM_CHANGES.md`, `docs/UPDATING_FROM_UPSTREAM.md` and `retro.properties.example`.
  The upstream README is now in `docs/UPSTREAM_README.md`.
- `.gitignore` entries for signing files (`retro.properties`, `*.jks`, `*.keystore`,
  `keystore.properties`, `public.properties`).

## [7.0.3-custom.3] - 2026-10-20 (versionCode 10705)

### Changed
- System media controls (media session custom actions, shown by Android 13+ media controls,
  Android Auto and other controllers): **shuffle and repeat are replaced by player volume
  down / up**. Each tap changes Retro's own player volume by 10 % (clamped to 0–100 %). The buttons
  work while the app UI is closed, and the now playing slider stays in sync. The volume-down
  button shows the "volume off" icon at 0 %.
- Shuffle and repeat are still available in the app UI.

## 7.0.3-custom.2 - 2026-10-19 (versionCode 10704)

### Changed
- The now playing volume slider now controls **Retro's own player volume (0–100 %)** instead of
  the system media volume, so Retro can be turned down relative to other apps.
  - 100 % is unity gain (normal playback at the system volume, never a boost). Lower values only
    attenuate, and 0 % is silent. It uses a squared curve (50 % ≈ −12 dB, 10 % = −40 dB).
  - The level is saved (default 100 %, including for existing installs) and applied immediately
    and whenever a player or track is created.
  - It's multiplied with ducking, fade in/out and crossfade, so they never exceed the user's level.
  - The − / + icons step by 5 %. Hardware volume buttons keep changing the system volume and no
    longer move the slider.

### Fixed
- During crossfade, the incoming track now starts silent before ramping up, so it can't play
  briefly at full volume.

## 7.0.3-custom.1 - 2026-10-19 (versionCode 10703)

### Added
- Artist and album-artist tags separated by `;` (e.g. `Artist A; Artist B`, also `A;B`) are split
  into individual artists in:
  - the Artists list, album artists and artist search
  - artist detail pages
  - "recently added" and "top played" artists
  - "Go to artist" navigation and the online artist-image lookup

  Single-artist tags behave as before. Names with `/` or `,` (e.g. `AC/DC`) aren't split in the library.
- Tapping an artist name on the Card and Material now playing screens opens that artist (upstream
  showed a placeholder message).

### Changed
- The "Volume controls" option for the now playing screen is now on by default, so the volume
  slider shows on all themes that have one. At this point the slider still controlled system volume.

[Unreleased]: https://github.com/oiuy-retro/RetroMusicPlayer-custom/compare/v7.0.3-custom.3...volume-artists
[7.0.3-custom.3]: https://github.com/oiuy-retro/RetroMusicPlayer-custom/releases/tag/v7.0.3-custom.3
