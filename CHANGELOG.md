# Changelog

All notable changes to this custom build of Retro Music Player are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

All custom releases keep upstream's version name **7.0.3** and are built from upstream `dev` at
`a7d4d05` (6 commits after `v7.0.3`). They're distinguished by their versionCode and the
`-custom.N` name. Only custom.3 was published as a GitHub release; custom.1 and custom.2 were
shared as APK files. For changes in the app itself, see the
[upstream project](https://github.com/RetroMusicPlayer/RetroMusicPlayer).

## [Unreleased]

### Added (branch `feature/shuffle-percent-limit`, versionCode 10720, 7.0.3-custom.7-beta.4)
- Shuffle settings: the number next to each slider is now a box you can type into. Press Done or
  leave the box to apply it. It's kept inside the slider's range, still keeps min/max pairs in
  order, and empty or invalid input goes back to the old value.

### Fixed (branch `feature/shuffle-percent-limit`, versionCode 10719, 7.0.3-custom.7-beta.3)
- The app could freeze while changing Folder/album block limits (time and percent). Block shuffle
  was slow on big libraries with small blocks and ran on the main thread after every setting change.
  Block shuffle is now fast on any library size and runs in the background.
- Changing shuffle settings no longer reshuffles the queue on every slider move. The queue is
  reshuffled once, when you leave the Shuffle settings page, and only if something changed.

### Fixed (branch `feature/shuffle-percent-limit`, versionCode 10718, 7.0.3-custom.7-beta.2)
- On the Shuffle settings page a Minimum slider could be set above its Maximum. Each min/max pair
  (songs, minutes and percent) is now linked while dragging: moving the minimum above the maximum
  raises the maximum to match, and moving the maximum below the minimum lowers the minimum.
  Inverted values saved by earlier builds are put back in order when read.

### Added (branch `feature/shuffle-percent-limit`, versionCode 10717)
- Folder or album blocks have a third limit, **Limit by percent of album/folder** (off by default,
  25 to 50 %). Each block gets a random percent in that range of its album's or folder's size,
  rounded up to at least 1 song. With other limits on, the block ends at whichever is reached
  first.

### Added
- Documentation for the custom build: README, this changelog, `docs/BUILDING.md`,
  `docs/CUSTOM_CHANGES.md`, `docs/UPDATING_FROM_UPSTREAM.md` and `retro.properties.example`.
  The upstream README is now in `docs/UPSTREAM_README.md`.
- `.gitignore` entries for signing files (`retro.properties`, `*.jks`, `*.keystore`,
  `keystore.properties`, `public.properties`).
- A **Shuffle** settings page (Settings > Shuffle) with a choice of shuffle modes: True random
  (the original shuffle and the default), Folder or album blocks, Genre blocks, Artist blocks,
  Spread out artists, Weighted (favorites, most played or least played) and Spotify-style.
  Each mode has its own options. A separate No repeats switch works with any mode and keeps its
  history between sessions, with a Reset shuffle history button. General options: reshuffle when
  the queue repeats with Repeat all, leave out songs shorter than a set length, and whether
  skipped songs count as played. Every block mode (folder or album, genre, artist) can end a
  block by number of songs, by time, or both, each with its own switch and min/max. With both
  on, the block ends at whichever limit is reached first. The shuffle buttons on the Albums and
  Artists tabs use the chosen shuffle mode (before, they always played whole albums or artists
  in a random album/artist order). Changing a shuffle setting while shuffle is on reshuffles the
  songs after the current one. Fixed in 10714: on the Shuffle page the "Maximum songs per block"
  slider (Folder/album and Artist blocks) sat below the time sliders and stayed active when the
  song limit was off; Genre's maximum minutes also stayed active with the time limit off. Each
  limit now shows as switch, minimum, maximum. Fixed in 10715: starting a shuffled list (Songs
  tab button, Albums/Artists tab buttons, shuffle all, album/artist/playlist pages, shortcuts,
  Android Auto) shuffled the queue twice and could leave the playing song out of step with Up
  Next. The queue is now shuffled once, with the starting song first. In 10716: every slider
  in the app gives a short tick per step while dragging, the same as HeliBoard's settings
  sliders (settings sliders, player seek bars, player volume, sleep timer, playback speed and
  pitch, audio fade; the seek bars tick every 5 seconds). The block defaults are 2 to 4 songs and 7 to 15 minutes for every block mode (values you
  already changed are kept). versionCode 10716.

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
