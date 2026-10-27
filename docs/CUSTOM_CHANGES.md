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
| `service/AudioManagerPlayback.kt`, `service/PlaybackManager.kt`, `service/MusicService.kt` | Resume after other media (see below). |
| `res/xml/pref_audio.xml`, `res/values/strings.xml` | `resume_after_focus_loss` switch and its English strings. |
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

## Resume after other media

- Preference `resume_after_focus_loss` (`PreferenceUtil.isResumeAfterFocusLossEnabled`), default
  off, shown directly under Always play (`manage_audio_focus`). Always play is unchanged.
- It only acts on `AUDIOFOCUS_LOSS` (the permanent loss most video and music apps cause) while
  Always play is off. `AUDIOFOCUS_LOSS_TRANSIENT` and ducking are handled as upstream does.
- On that loss, `AudioManagerPlayback` pauses as before. If it was playing and the setting is on,
  it sets `isPausedByLossOfFocus` and keeps its focus request (it doesn't call `abandonFocus()`).
- Android removes an app from the focus stack after a permanent loss, so `AUDIOFOCUS_GAIN`
  usually doesn't come back. Instead, Retro watches whether other media is still playing:
  - API 26+: while a resume is pending, an `AudioManager.AudioPlaybackCallback` is registered
    (on the main thread) and also checked once right after registering. The callback is only a
    change trigger: the config list also holds paused and stopped players (Retro's own and the
    video app's), and apps can't read a player's state. Other media counts as active if
    `AudioManager.isMusicActive` is true or the audio mode isn't `MODE_NORMAL` (call or ringtone).
  - When nothing else is playing, a 1 s debounce starts. Any new playback cancels it, so the
    gap between two videos doesn't trigger a resume. Until other media has been seen after the
    loss, the delay is 3 s instead, because the other app usually takes focus a moment before
    its playback starts. There's no periodic polling on API 26+.
  - After the debounce, if media is still active or focus isn't granted (Android 15+ can refuse
    focus to background apps), Retro checks again once a second, up to 5 times, then waits for
    the next playback change.
  - Optional session watch (`MediaSessionWatcher`): if the user grants notification access to
    the empty `MediaStateListenerService` ("Detect paused videos" in Audio settings), other
    media counts as active only while another app's media session is playing or buffering
    (`MediaSessionManager.getActiveSessions` plus controller callbacks). This catches a paused
    browser video, whose audio stream stays open so `isMusicActive` stays true. Without access,
    `isMusicActive` is used. Notification contents are never read and nothing is stored.
  - While a resume is pending, `MusicService.onUnbind` doesn't call `stopSelf()` and the service
    stays in the foreground (Android 17 refuses audio focus to background apps without one).
  - Temporary diagnostic: `ResumeLog` keeps the last 200 events in memory and in
    `files/resume_log.txt` (also logcat tag `ResumeAfterFocus`). "Copy resume log" in Audio
    settings copies them. Remove once the feature is confirmed working.
  - API 24-25: the same check runs once a second, starting 1 s after the loss. Retro resumes
    after 1 idle check (3 before other media has been seen).
  - When the delay ends, Retro checks once more, then requests focus. If that's granted, it
    calls `start()` and `onPlayStateChanged()`, like the transient path. If focus is denied
    (for example during a call), it keeps waiting for the next playback change. If
    `AUDIOFOCUS_GAIN` does arrive, it resumes the same way.
- `start()`, `pause()` and `stop()` all call `clearPendingFocusResume()`, so a manual play,
  pause or stop cancels the pending resume. `PlaybackManager.pause()` also clears it when
  playback is already paused. Clearing also unregisters the playback callback and cancels any
  pending check. Waiting also stops as soon as the setting is turned off or Always play is
  turned on (`MusicService.onSharedPreferenceChanged` calls
  `PlaybackManager.onFocusResumeSettingsChanged()`), and when the service is destroyed (`release()` calls `stop()`).
- Limitations: an app that keeps playing (or keeps an active media stream) means no resume.
  On Android 10+ the configs given to apps hide which app is playing (no uid), so any other
  media source keeps Retro paused. A focus denial with no later playback change (rare) leaves
  Retro waiting. If Android stops the paused service, nothing resumes.
