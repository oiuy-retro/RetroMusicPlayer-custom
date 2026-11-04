# Custom changes: technical notes

This describes how the custom changes are implemented, for anyone maintaining the fork or
merging upstream updates. Paths are relative to `app/src/main/java/code/name/monkey/retromusic/`
unless stated otherwise. To see the exact diff against upstream:

```bash
git diff a7d4d05 volume-artists -- app/
```

## Files touched

| File | Change |
|---|---|
| `util/ArtistSeparator.kt` | Adds `"; "` to the display separators. Adds the library splitter (`splitLibrary`, `isMultiArtist`, `containsArtist`, `key`), synthetic artist ids and `navigationArtistId`. |
| `model/Artist.kt` | Adds an optional `displayName` constructor parameter used by split artists. |
| `repository/ArtistRepository.kt` | Splits artists / album artists on `;` (see below). |
| `fragments/base/AbsPlayerFragment.kt` | `goToArtist(activity, artistName = null)` opens the matching split artist. |
| `fragments/player/card/CardPlaybackControlsFragment.kt`, `fragments/player/material/MaterialControlsFragment.kt` | Tapping an artist link calls `goToArtist` (upstream showed a TODO toast). |
| `helper/menu/SongMenuHelper.kt`, `fragments/albums/AlbumDetailsFragment.kt` | "Go to artist" uses `ArtistSeparator.navigationArtistId`. |
| `glide/artistimage/ArtistImageFetcher.kt` | The artist-image lookup also splits on `;` (first artist is used). |
| `fragments/other/VolumeFragment.kt` | Rewritten: the slider edits `PreferenceUtil.playerVolume` instead of `STREAM_MUSIC`. |
| `service/PlayerVolume.kt` (new) | Maps percent → linear gain. |
| `service/playback/Playback.kt` | New `setPlayerGain(gain)` with a no-op default, so `CastPlayer` doesn't need changes. |
| `service/AudioManagerPlayback.kt` | Holds `playerGain` / `requestedVolume`, `scaledVolume()`, and abstract `onPlayerGainChanged()`. |
| `service/RetroExoPlayer.kt`, `service/CrossFadePlayer.kt`, `service/AudioFader.kt` | Apply the gain (see below). |
| `service/PlaybackManager.kt` | `setPlayerGain()` forwards to the active playback. |
| `service/MusicService.kt` | Applies the gain on preference change. Volume custom actions, `adjustPlayerVolume()`, intent actions. |
| `service/MediaSessionCallback.kt` | Handles the volume custom actions. |
| `util/PreferenceUtil.kt`, `Constants.kt` | The `toggle_volume` default is now `true`. New `player_volume` int preference (default 100). |
| `res/xml/pref_now_playing_screen.xml` | `toggle_volume` `defaultValue="true"`. |
| `res/values/strings.xml` | `action_player_volume_down` / `action_player_volume_up` (English only). |
| `app/build.gradle.kts` | `versionCode` bumps only. |

## Player volume (gain)

### Preference and curve

- The preference is `player_volume`, an int 0–100 (`PreferenceUtil.playerVolume`). It's clamped
  on read and write. It defaults to **100** when unset, so existing installs start at unity.
- `PlayerVolume.gainForPercent(p) = (p / 100)²`, so 100 → 1.0, 50 → 0.25 (≈ −12 dB),
  10 → 0.01 (−40 dB), 0 → 0.
- `PlayerVolume.currentGain` reads the preference and applies the curve.

### How the gain is combined

`AudioManagerPlayback` (the base of both local players) keeps two values:

- `requestedVolume`: the last value passed to `Playback.setVolume(vol)` by existing code. That's
  fade in/out (`AudioFader.startFadeAnimator`), ducking (`Volume.DUCK = 0.2`) and focus regain
  (`Volume.NORMAL = 1.0`).
- `playerGain`: the user's slider gain. It's initialised from `PlayerVolume.currentGain` when the
  player object is created.

The volume actually sent to the platform player is
`scaledVolume(vol) = (vol × playerGain).coerceIn(0, 1)`. Because the gain is ≤ 1 and is applied
by multiplication, ducking and fades keep their relative behavior and can never exceed the
user's level.

Per implementation:

- **`RetroExoPlayer`** (used when crossfade is off; gapless via ExoPlayer):
  - `setVolume()` stores `requestedVolume` and sets `player.volume = scaledVolume(vol)`.
  - The gain is also applied in `init` and after `onPlayerError()` recreates the ExoPlayer.
  - Gapless track changes reuse the same ExoPlayer, so the volume carries over.
- **`CrossFadePlayer`** (two `MediaPlayer`s):
  - `setVolume()` applies `scaledVolume()` to the current player.
  - `setDataSourceImpl()` applies `scaledVolume(requestedVolume)` to every freshly reset player.
  - `switchPlayer()` sets the incoming player to 0 before `start()`.
  - `AudioFader.createFadeAnimator()` takes a `targetVolume` lambda (`{ scaledVolume(requestedVolume) }`),
    so the crossfade ramps the incoming player 0 → target and the outgoing player target → 0. A
    gain change during a crossfade is picked up on the next animation frame.
  - `onPlayerGainChanged()` does nothing while a crossfade animator is running.
- **`CastPlayer`** keeps the no-op default, so the gain isn't applied to Cast.

### How a change reaches the player

1. `VolumeFragment` (slider or − / + buttons, 5 % steps) or a media-control button (10 % steps)
   writes `PreferenceUtil.playerVolume`.
2. `MusicService.onSharedPreferenceChanged` sees `PLAYER_VOLUME`. It calls
   `playbackManager.setPlayerGain(PlayerVolume.currentGain)`, then
   `updateMediaSessionPlaybackState()` to refresh the media-control icons.
3. `AudioManagerPlayback.setPlayerGain()` stores the gain and calls `onPlayerGainChanged()`, which
   re-applies `scaledVolume(requestedVolume)`.
4. When `PlaybackManager` creates or switches players (startup, crossfade on/off), the new player
   reads the gain in its constructor.

`VolumeFragment` registers a preference listener while it's resumed and reads the saved value in
`onResume()`, so every theme's slider shows the same level. It no longer uses `AudioManager` or
`AudioVolumeObserver`. The Circle theme still has its own system-volume dial, and
`MusicService`'s "pause on zero volume" still observes the system volume.

## Media session volume actions

- `MusicService.setCustomAction()` used to add `CYCLE_REPEAT` and `TOGGLE_SHUFFLE`. It now adds:
  - `PLAYER_VOLUME_DOWN` (`…playervolumedown`), using `ic_volume_down`, or `ic_volume_off` at 0 %
  - `PLAYER_VOLUME_UP` (`…playervolumeup`), using `ic_volume_up`
- `MediaSessionCallback.onCustomAction()` maps them to
  `musicService.adjustPlayerVolume(∓PLAYER_VOLUME_STEP)`, where `PLAYER_VOLUME_STEP = 10`.
  `onStartCommand` also accepts the two action strings as intent actions.
- `adjustPlayerVolume(delta)` clamps to 0–100 and only writes the preference when the value
  changes. The preference listener then does the rest.
- The old `CYCLE_REPEAT` / `TOGGLE_SHUFFLE` handlers are still in `onCustomAction`. They're just no
  longer advertised.
- `PlayingNotification` (the only notification implementation) wasn't changed. It never had
  shuffle/repeat buttons.

## Artist splitting

### Two splitters

`ArtistSeparator` has two different splitters:

- `split()`, which upstream uses for display and links: it splits on `"; "`, `;`, `/`, `,`,
  `feat.` and `ft.`. This is unchanged apart from listing `"; "` explicitly.
- `splitLibrary()`, used for library grouping: it splits only on `\s*;\s*`, trims, drops empty
  parts and de-duplicates case-insensitively (`key()` = trimmed + `lowercase(Locale.ROOT)`).
  `isMultiArtist()` is true when it yields more than one name. Keeping it to `;` avoids breaking
  names like `AC/DC`.

### Synthetic ids

Artists that only exist inside multi-artist tags have no MediaStore artist id. They get
`syntheticArtistId(name) = -(1000 + (key(name).hashCode() & 0x7FFFFFFF))`, which is always
≤ −1000. That keeps them away from `-1` (empty) and `-2` (`VARIOUS_ARTISTS_ID`), and
`isSyntheticArtistId(id)` tests `id <= -1000`. This lets the existing id-based navigation
(`artistDetailsFragment` with `extra_artist_id`), home, search and Android Auto work without
navigation-graph changes.

### `ArtistRepository`

- **`splitIntoArtists(albums)`** (Artists list, search, recently added, top played):
  - If no song has a multi-artist tag, it uses exactly the upstream code path (group albums by
    `artistId`).
  - Otherwise single-artist songs are grouped per album under their first song's `artistId`, as
    before. A name → id map is built from those songs.
  - Each multi-artist song is then added once per split name, to the existing real-id group if
    that name already exists, or else to a synthetic-id group.
  - Each `Artist` gets an explicit `displayName`.
- **`splitIntoAlbumArtists(albums)`**: same idea, keyed by the album-artist string. Albums with
  `A; B` are added to each name's group, reusing an existing (case-insensitive) single-artist key.
  "Various Artists" handling is unchanged.
- **`artist(id)`**:
  - A synthetic id goes to `splitArtist()`. That loads all songs, finds the name whose synthetic
    id matches, and returns every song whose tag contains that name, including single-artist songs
    with the same name.
  - A real id runs the upstream query, then adds multi-artist songs that contain the artist's
    name (`multiArtistSongsFor`, a `LIKE '%name%' AND LIKE '%;%'` query filtered with
    `containsArtist`).
  - If there are no extra songs, the result is the upstream result.
- **`albumArtist(name)`**: the upstream exact match, plus albums whose album artist is a `;` list
  containing the name.
- **Search** (`artists(query)`, `albumArtists(query)`): split co-artists that only came along
  because they share a tag with a match are filtered out unless their own name contains the query.

### Navigation

`ArtistSeparator.navigationArtistId(song, clickedName)` returns `song.artistId` for single-artist
songs. For multi-artist songs it returns the synthetic id of the tapped name: an exact match, then
a "contains" match, then the first artist. It's used by `goToArtist()`, the song menu and the
album page's artist image.

The combined MediaStore artist (`"A; B"`) still exists in MediaStore. Anything that navigates
with that raw id shows the combined entry as upstream did.

`Artist.name` now prefers `displayName`. The unknown-artist and Various Artists display mapping
still applies.

## Shuffle modes

Settings > Shuffle (`fragments/settings/ShuffleSettingsFragment.kt`, `res/xml/pref_shuffle.xml`)
chooses how the queue is reordered when shuffle is turned on or a shuffled queue is started.

### Files

| File | Change |
|---|---|
| `shuffle/ShuffleEngine.kt` (new) | The algorithms. Pure Kotlin, no Android types; tested in `app/src/test/.../shuffle/ShuffleEngineTest.kt` with fake songs and a seeded `Random`. |
| `shuffle/ShuffleQueueBuilder.kt` (new) | Reads settings and library data, converts `Song`s to `ShuffleTrack`s, runs the engine. |
| `shuffle/ShuffleSettings.kt` (new) | Preference keys and readers. |
| `shuffle/ShuffleHistory.kt` (new) | No-repeat history, one song id per line in `files/shuffle_history.txt`. |
| `helper/ShuffleHelper.kt` | New `makeShuffleList(context, list, current)` overload that routes through the builder. The original function is unchanged. |
| `service/MusicService.kt` | `setShuffleMode` and `openQueue` use the new overload. `reshuffleForLoopIfNeeded()` runs at the start of `prepareNextImpl()`. The `META_CHANGED` handler reports the previous song to the no-repeat history. |
| `service/MediaSessionCallback.kt` | "Shuffle all" (Android Auto and other controllers) uses the new overload. |
| `fragments/settings/MainSettingsFragment.kt`, `SettingsFragment.kt`, `res/layout/fragment_main_settings.xml`, `res/navigation/settings_graph.xml` | The Shuffle entry, its title and navigation. |
| `res/values/strings.xml`, `res/values/arrays.xml` | English strings and list values. |
| `app/build.gradle.kts` | `testImplementation("junit:junit:4.13.2")` and the versionCode. |

### Behavior

The whole order is computed when shuffle starts, so the queue screen shows the real upcoming
songs. The current song always stays first. When every option is at its default (True random, No
repeats off, minimum length 0), the original `ShuffleHelper` code runs, so the default behavior is
unchanged. If building the order fails for any reason, it also falls back to the original code.

- **True random**: every order is equally likely.
- **Block length (all block modes)**: each block mode has two limits, each with its own switch:
  number of songs (min/max) and time (min/max minutes). For each block a random target is picked
  between min and max for every limit that's on. Songs are added while the block is below all of
  its targets, so the block ends at whichever is reached first. Time counts the durations of the
  songs in the block, and the song that crosses the time target plays in full. A group smaller
  than the targets plays all of its songs. If both switches are off, the song limit is used.
  Defaults: folder/album and artist blocks use songs only (3 to 6; time 15 to 30 min when turned
  on), genre blocks use time only (20 to 40 min; songs 5 to 10 when turned on). Existing settings
  keep their keys (`shuffle_group_min/max`, `shuffle_artist_min/max`,
  `shuffle_genre_min/max_minutes`), so earlier values carry over unchanged. New keys:
  `shuffle_{group,artist,genre}_use_songs`, `shuffle_{group,artist,genre}_use_time`,
  `shuffle_{group,artist}_min/max_minutes` and `shuffle_genre_min/max` (songs). All block modes
  share `ShuffleEngine.countBlocks` with a `BlockLimits` value.
- **Entry points**: every queue shuffle goes through `ShuffleHelper.makeShuffleList(context, …)` →
  `ShuffleQueueBuilder`. The Albums and Artists tab shuffle buttons are upstream's own feature
  (random album/artist order, whole albums, shuffle off). When a mode other than True random is
  chosen, they now open all songs with `openAndShuffleQueue` instead, so the mode applies.
  `MusicService` listens for `shuffle_*` preference changes and, after 0.8 s, reshuffles the
  part of the queue after the current song if shuffle is on.
- **Settings layout**: each limit is a switch followed by its minimum and maximum slider; the
  sliders depend on the switch. `ShuffleSettingsLayoutTest` checks this order in
  `pref_shuffle.xml`. `FabShufflePathTest` (Robolectric) runs `ShuffleHelper.makeShuffleList`
  with prefs stored as the settings screen stores them, twice in a row as the Songs tab button
  does (`openQueue` with shuffle on, then `setShuffleMode`).
- **Folder or album blocks**: the grouping is the album (`albumId`) or the song's parent folder
  (taken from the file path). A random song is picked, then songs from its group follow until the
  block limits are reached. "In order from the first pick" continues by track number (album) or
  file path (folder) from the pick and wraps to the group's earlier songs. "Random within block"
  takes random songs from the group. The next pick prefers a different group.
- **Genre blocks**: a random song is picked and random songs of the same genre follow until the
  block limits are reached. The next pick prefers a different genre. Genres come from
  MediaStore (the `GENRE` column on Android 11 and newer, the genre tables on older versions).
  Untagged songs form an "Unknown" group, or can be left out.
- **Artist blocks**: like album blocks, grouped by the song's first artist after this fork's `;`
  split. "In order" goes by album, then track number.
- **Spread out artists**: random, but no artist plays again within N songs (1 to 20, default 5).
  All of a song's `;`-split artists count. If no song fits, the song whose artist was last heard
  longest ago is used.
- **Weighted**: Efraimidis-Spirakis weighted random order. Each song's weight is
  `1 + strength × score`, with strength 0 to 10 (default 5) and score 0 to 1. Favorites score 1
  for songs in the Favorites playlist. Most played uses the rank in the existing play-count store
  (`SongPlayCountStore`), and least played is the reverse, with never-counted songs scoring 1.
- **Spotify-style**: after Lukáš Poláček, "How to shuffle songs?", Spotify Engineering, 28
  February 2014 (<https://engineering.atspotify.com/2014/02/how-to-shuffle-songs/>, archived at
  <https://web.archive.org/web/20220215030739/https://engineering.atspotify.com/2014/02/how-to-shuffle-songs/>).
  Each artist's songs are shuffled, then spread evenly along the queue with a random starting
  offset per artist and up to ±20 % random variation in spacing, and everything is sorted by
  position. There's no Apple mode because Apple hasn't published its algorithm.

Options for all modes:

- **No repeats**: songs already in the history are placed after all unplayed songs, and each
  part is ordered by the active mode. When the share of the queue's songs that have been played
  reaches the set percentage (50 to 100, default 100), those songs are removed from the history.
  A song is added when it stops being the current song after more than half of it has played,
  the same rule as the play count. With "Skipped songs count as played" on, it's added either way.
- **Reshuffle when the queue repeats**: with shuffle and Repeat all on, when the last song starts,
  the other songs are reshuffled and the current song moves to the end, so the next loop has a new
  order.
- **Skip songs shorter than**: songs shorter than N seconds (0 to 120, 0 = off) are left out of
  the shuffled queue. They come back when shuffle is turned off, because the unshuffled queue is
  kept separately as before.

### Limits

- Settings apply the next time shuffle is turned on or a shuffled queue is started. They don't
  reorder a queue that's already shuffled.
- Genre blocks depend on genre tags in MediaStore. Songs with several genres use the one
  MediaStore reports.
- "Shuffle all" from the Songs list still starts on a random song chosen before the mode runs.
  With No repeats on, that first song can be one that was already played.
- The Artists tab shuffle button is unchanged. It orders artists randomly and plays each artist's
  songs together with shuffle off.
- Library data for Weighted and Genre blocks is read when shuffle starts, which can take a moment
  on large libraries.
