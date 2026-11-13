# Loudness normalization (custom)

## What it does

- Imports/exports a portable JSON loudness file compatible with the shared schema used by
  [jellyfin-loudness-exporter](https://github.com/oiuy-retro/jellyfin-loudness-exporter)
  (pinned commit in PINNED_EXPORTER.txt).
- Can measure tracks on the phone with MIT **libebur128** (EBU R128 integrated loudness + true peak)
  via MediaCodec PCM decode.
- Applies whole-track gain during playback (not dynamic-range compression).
- Strength 0–100%, max cut, optional daily local-time window (overnight supported). Max cut
  limits how far a loud track is turned down; quiet tracks are never raised in this build.
- Matches songs by audio hash first, then by exact path. See AUDIO_HASH.md.

## Limits (honest)

- No claim that a specific Windows Jellyfin server file was verified on-device in this PR.
- Jellyfin gains have ambiguous origin (`lufs-or-replaygain-unspecified`); this build never invents LUFS from them.
- Positive boost requires true-peak metadata; otherwise attenuation-only.
- MediaCodec may fail on some codecs/containers; those files are reported as unsupported.
- Matching uses the `jle-audio-v1` audio hash when the file has one, otherwise exact `rootId` +
  `relativePath` under the configured filesystem root. Basename-only matching is not used.
- Android producer fields are defined in ANDROID_PRODUCER.md for teammate review; exporter still marks them RESERVED.
- First scan of a huge library is long; job is a user-started foreground service and is cancellable.

## Device checklist

See the pull request description.
