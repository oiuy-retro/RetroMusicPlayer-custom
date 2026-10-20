# Retro Music Player — Custom Build

A personal build of Retro Music Player with a player-only volume slider, volume buttons in the media controls, and better handling of multi-artist tags.

> **This is an unofficial, private custom build.** It is based on
> [Retro Music Player](https://github.com/RetroMusicPlayer/RetroMusicPlayer) by Hemanth Savarla
> and the Retro Music Player contributors, and is distributed under the same
> [GNU General Public License v3.0](LICENSE.md). It is not affiliated with or supported by the
> upstream project — please don't report problems with this build to them.
>
> The original upstream README is kept in [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md).

- Based on upstream `dev` at commit `a7d4d05` (6 commits after the `v7.0.3` tag).
- App version: **7.0.3** (version name unchanged), current custom versionCode **10705**.
- Flavor: **fdroid** (no Google Play services, billing or Chromecast).
- See [CHANGELOG.md](CHANGELOG.md) for the history of the custom releases.

## What's different from upstream

### 1. Now playing volume slider controls Retro's own volume

- The existing "Volume controls" option (Settings → Now playing) is now **on by default**, so the
  slider shows up on the now playing screen. If you had switched it off before, it stays off.
- The slider changes **Retro's own player volume only (0–100 %)**, not the system media volume.
  This is handy when Retro keeps playing while another app (e.g. a video) has audio focus: you can
  turn Retro down without touching the phone's volume.
- **100 % = normal playback at the current system volume** (unity gain, never a boost). Lower
  values only attenuate, and 0 % is silent.
- The slider uses a squared curve so the low end stays usable: 50 % ≈ −12 dB, 10 % = −40 dB.
- The − / + icons next to the slider step the volume by 5 %.
- The level is saved and shown the same on every screen and theme, and survives app restarts.
  It defaults to 100 %.
- Hardware volume buttons still change the system volume as usual.
- Ducking, fade in/out and crossfade still work. They are scaled to your level and never go above it.
- Shown on these now playing themes: Normal (default), Adaptive, Blur, BlurCard, Card, Classic,
  Color, Fit, Flat, Full, Gradient, Material, MD3, Peek, Plain, Simple, and the lock screen controls.
  **Circle** keeps its own round dial, which still controls the *system* volume. **Tiny** has no slider.

### 2. Volume buttons in the system media controls

- In the media session controls (what Android 13+ shows in the notification shade and lock screen,
  and what Android Auto and other controllers read), **shuffle and repeat are replaced by
  "volume down" and "volume up"**.
- Each tap changes Retro's player volume by **10 %**, clamped to 0–100 %. The now playing slider
  updates to match.
- It works with the app closed, because the music service handles it.
- At 0 % the volume-down button shows the "volume off" icon. Both buttons always stay visible.
- Play/pause, previous and next are unchanged. Shuffle and repeat are still available inside the app.

### 3. Artists separated by `;` are split into individual artists

- Artist and album-artist tags like `Artist A; Artist B` are split into **Artist A** and
  **Artist B**. Bare `;` works too (`A;B`, `A ; B;`), and empty parts are ignored. Matching ignores case.
- This affects:
  - the Artists list, where the song shows up under each artist
  - album artists
  - artist search
  - artist detail pages
  - "recently added" and "top played" artists
  - "Go to artist" from now playing, the song menu and the album page
  - the online artist-image lookup
- If Artist A also has normal single-artist songs, the split songs are added to that same artist,
  so you don't get duplicates.
- **Songs with a single artist behave exactly as before.**
- Only `;` splits library artists, so names like `AC/DC` or `Earth, Wind & Fire` stay intact.

### 4. Tap-to-artist on the Card and Material themes

- Tapping an artist name on the **Card** and **Material** now playing screens now opens that
  artist's page. Upstream only showed a placeholder message.

## Install

Requirements: **Android 7.0 (API 24) or newer.**

1. Download the latest `.apk` from this repository's **Releases** page. The repo is private, so you
   need to be signed in to GitHub with access to it.
2. **Uninstall any Play Store or F-Droid copy of Retro Music Player first.** This build uses the
   same package name (`code.name.monkey.retromusic`) but a different signing key, so Android won't
   install it over the official app. Uninstalling removes that copy's settings and playlists. Use
   Retro's backup feature first if you want to keep them.
3. Open the APK on your phone. If asked, allow your browser or file manager to
   **install unknown apps** (Settings → Apps → Special app access → Install unknown apps).
4. Later custom releases install as normal updates over earlier ones, because they're signed with
   the same key and have a higher versionCode.

## Building from source

Short version (details and troubleshooting are in [docs/BUILDING.md](docs/BUILDING.md)):

- **JDK 21** (the project targets Java 21; JDK 17 won't work)
- **Android SDK** with platform **android-36** and build-tools 36
- The Gradle wrapper (Gradle 8.14.2) is checked in, so you don't need your own Gradle install.

```bash
git clone https://github.com/oiuy-retro/RetroMusicPlayer-custom.git
cd RetroMusicPlayer-custom
cp retro.properties.example retro.properties   # then edit it, see below
./gradlew assembleFdroidRelease
# -> app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

### Signing

`app/build.gradle.kts` reads signing details from a file called **`retro.properties`** in the
repository root. That file is listed in `.gitignore` and must **never** be committed.
[`retro.properties.example`](retro.properties.example) is the template:

```properties
storeFile=/path/to/your-release-keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

If `retro.properties` is missing, release builds are signed with the Android **debug** key instead.
They still install, but they can't update an APK signed with your release key. To update an
existing install, always sign with the **same keystore** as before. Keep a backup of it.

## Known limitations

- Builds are tested by building and signing only. There's no automated test suite, so check
  changes on a real device.
- Only the **fdroid** flavor is built and used. The `normal` flavor (Play/Cast) compiles from the
  same code but hasn't been tried.
- The player volume isn't applied to Chromecast playback (not included in the fdroid flavor anyway).
- The **Circle** theme's round dial still controls the system volume. **Tiny** has no slider.
- "Pause on zero volume" still watches the *system* volume. Moving Retro's slider to 0 mutes Retro
  but doesn't pause it.
- If another app asks Retro to duck, ducking is applied on top of your slider level. Keeping Retro
  playing under a video also needs the "manage audio focus" setting enabled.
- Android 13+ shows at most two extra media-control buttons. Some phone makers' media panels or
  older Android versions may not show them at all. Favorite isn't in the system media controls,
  same as upstream.
- On Android 12 and older, the media notification itself has favorite / previous / play-pause /
  next / close buttons as before, with no shuffle/repeat or volume buttons.
- Song lists and the now playing text still *display* artist names split on `/` and `,` as well
  (upstream behavior), so `AC/DC` may show as "AC, DC" there. The library itself only splits on `;`.
- A few places still link to the original combined "A; B" artist entry (e.g. an album's
  album-artist link opens by the full album-artist text).
- Opening the page of an artist that only exists inside combined tags scans all songs. That's about
  the same work as loading the Artists list, but it hasn't been checked on very large libraries.
- Not installable over the Play Store / F-Droid app (different signing key, see Install).

## Staying in sync with upstream

This fork keeps upstream's history, so new upstream versions can be merged in. Upstream's default
branch is `dev`, and releases are tagged like `v7.0.3`. The short version:

```bash
git remote add upstream https://github.com/RetroMusicPlayer/RetroMusicPlayer.git   # once
git fetch upstream --tags
git checkout volume-artists
git merge upstream/dev          # or a release tag, e.g. v7.0.4
# resolve conflicts, bump versionCode, build, test, push, release
```

The full walkthrough is in [docs/UPDATING_FROM_UPSTREAM.md](docs/UPDATING_FROM_UPSTREAM.md). It
covers which files tend to conflict, how to pick the next versionCode, and how to publish a release.

## More documentation

- [CHANGELOG.md](CHANGELOG.md): what changed in each custom release
- [docs/BUILDING.md](docs/BUILDING.md): build, signing and troubleshooting
- [docs/CUSTOM_CHANGES.md](docs/CUSTOM_CHANGES.md): technical details of the custom changes
- [docs/UPDATING_FROM_UPSTREAM.md](docs/UPDATING_FROM_UPSTREAM.md): merging new upstream versions
- [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md): the original upstream README

## License and credits

Retro Music Player is © Hemanth Savarla and the Retro Music Player contributors, licensed under
the [GNU GPL v3.0](LICENSE.md). This custom build is a modified version of it and is under the
same license. Source files keep their original copyright headers. All credit for the app itself
goes to the upstream authors: <https://github.com/RetroMusicPlayer/RetroMusicPlayer>.
