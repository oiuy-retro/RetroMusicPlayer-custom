# Updating from upstream

This fork keeps the full upstream history. Upstream development happens on
[`RetroMusicPlayer/RetroMusicPlayer`](https://github.com/RetroMusicPlayer/RetroMusicPlayer)
branch **`dev`**, and releases are tagged like `v7.0.3`.

Branches in this repo:

- **`volume-artists`** is the default branch: upstream plus the custom changes. Releases are built from it.
- **`dev`** is a plain mirror of upstream `dev` at the time it was pushed. Don't commit custom work to it.

The examples below use `upstream` for the official repo and `mine` for this private repo. In a
fresh clone of this repo, the private repo is usually called `origin`, so substitute as needed.

## 1. Fetch upstream

```bash
git remote add upstream https://github.com/RetroMusicPlayer/RetroMusicPlayer.git   # first time only
git fetch upstream --tags
git log --oneline volume-artists..upstream/dev     # what's new upstream
```

Optionally refresh the mirror branch:

```bash
git push mine upstream/dev:refs/heads/dev
```

## 2. Merge into `volume-artists`

**Merging is recommended.** It keeps the published history intact, and you don't need to
force-push a branch that releases point to.

```bash
git checkout volume-artists
git merge upstream/dev            # or a release tag: git merge v7.0.4
```

Rebasing (`git rebase upstream/dev`) gives a linear history but rewrites `volume-artists`, so
you'd have to `git push --force-with-lease`. Only do that if nobody else uses the branch.

## 3. Resolve conflicts

The custom changes are concentrated in a few files. These are the most likely to conflict:

| File | What to keep |
|---|---|
| `app/build.gradle.kts` | Upstream's new `versionName` and dependencies. Then set `versionCode` as in step 4. |
| `repository/ArtistRepository.kt` | Upstream's query/sort changes, plus the `;` splitting (`splitIntoArtists`, `splitIntoMultiAlbumArtists`, `splitArtist`, `multiArtistSongsFor`, the filters in `artists(query)` / `albumArtists(query)`). |
| `util/ArtistSeparator.kt` | Both sets of separators, plus the library splitter and synthetic-id helpers. |
| `model/Artist.kt` | The `displayName` parameter and its use in the `name` getter. |
| `fragments/other/VolumeFragment.kt` | The player-volume version (no `AudioManager` / `AudioVolumeObserver`). |
| `service/MusicService.kt` | The `PLAYER_VOLUME` preference branch, `setCustomAction()` (volume actions), `adjustPlayerVolume()`, the `PLAYER_VOLUME_*` constants and intent actions. |
| `service/MediaSessionCallback.kt` | The `PLAYER_VOLUME_DOWN/UP` cases. |
| `service/AudioManagerPlayback.kt`, `RetroExoPlayer.kt`, `CrossFadePlayer.kt`, `AudioFader.kt`, `PlaybackManager.kt`, `playback/Playback.kt` | Everywhere the platform player volume is set, it must go through `scaledVolume(...)`. |
| `util/PreferenceUtil.kt`, `Constants.kt`, `res/xml/pref_now_playing_screen.xml` | `toggle_volume` default `true`, and the `player_volume` preference. |
| `fragments/player/card/…`, `fragments/player/material/…`, `fragments/base/AbsPlayerFragment.kt`, `helper/menu/SongMenuHelper.kt`, `fragments/albums/AlbumDetailsFragment.kt` | The `goToArtist(activity, name)` / `navigationArtistId` calls. |
| `README.md` | Keep **this fork's** README. If upstream changed theirs, copy it to `docs/UPSTREAM_README.md`, keep the note at the top, and keep the `../` path fixes. |

Tips:

- `git diff a7d4d05 volume-artists -- app/` (or against the previous merge base) shows exactly
  what the fork changed. It's useful as a checklist.
- If upstream adds a new player implementation, a new place that calls `MediaPlayer.setVolume` /
  `ExoPlayer.volume`, or new now playing themes, apply the gain and the volume fragment there too.
- If upstream itself adds a volume or artist-separator setting, prefer adapting to it over keeping
  duplicate logic.
- [CUSTOM_CHANGES.md](CUSTOM_CHANGES.md) explains how each part works.

After resolving:

```bash
git add -A && git commit        # completes the merge
```

## 4. Bump the versionCode

Android only installs an update when its `versionCode` is **higher** than the installed one, and
it's signed with the **same key**.

- `versionName`: use upstream's value (e.g. `7.0.4`).
- `versionCode`: use whichever is larger, `upstream_versionCode + 1` or `last_custom_versionCode + 1`.
  So far: 10703 (custom.1), 10704 (custom.2), 10705 (custom.3).
- Tag name: `v<versionName>-custom.<N>`, e.g. `v7.0.4-custom.1`. N restarts at 1 for a new upstream
  version name.

Edit `defaultConfig` in `app/build.gradle.kts`, and add an entry to [CHANGELOG.md](../CHANGELOG.md).

## 5. Build and test

```bash
./gradlew assembleFdroidRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

Check that the certificate SHA-256 matches previous releases, and see [BUILDING.md](BUILDING.md)
for signing setup. Then install the APK on a device **over the previous custom build** and check:

- The now playing slider changes Retro's volume, not the system volume. 100 % sounds the same as
  before, and the level survives a restart.
- The Android 13+ media controls show volume down/up, and they move the slider.
- A song tagged `A; B` appears under both A and B in Artists, and tapping each artist on Card or
  Material opens the right page.
- Normal single-artist songs, gapless playback, crossfade (if you use it) and ducking still work.

## 6. Push and release

Before pushing, make sure no secrets are staged or tracked:

```bash
git status --ignored                                    # retro.properties should only appear as ignored
git ls-files | grep -Ei 'jks|keystore|retro.properties' # should print nothing
```

Then push and release:

```bash
git push mine volume-artists
cp app/build/outputs/apk/fdroid/release/app-fdroid-release.apk RetroMusicPlayer-custom-7.0.4-custom.1.apk
gh release create v7.0.4-custom.1 --repo oiuy-retro/RetroMusicPlayer-custom \
  --target volume-artists --title "7.0.4-custom.1" \
  --notes "See CHANGELOG.md" \
  RetroMusicPlayer-custom-7.0.4-custom.1.apk
# gh creates the tag on volume-artists if it doesn't exist yet.
# *.apk is git-ignored, so the copied APK won't be committed by accident.
```

Notes:

- Upstream merges often touch `.github/workflows/*`. Pushing commits that change workflow files
  needs a GitHub token with the **`workflow`** scope (`gh auth refresh -h github.com -s workflow`).
- GitHub Actions is **disabled** in this private repo, so upstream's workflows (`android.yml` on
  pushes to `dev`, `release.yml` on any tag) don't run. Keep it that way unless you deliberately
  want them. `release.yml` expects upstream's signing secrets, which this repo doesn't have.
- Keep the repository and its releases **private**. The keystore and its passwords should never
  be uploaded anywhere.
