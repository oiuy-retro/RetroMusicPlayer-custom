# Android producer fields (schemaVersion 1 extension)

Pinned importer reference: oiuy-retro/jellyfin-loudness-exporter@952392ae3c7e6e2eb2f976eb9cd7d51ea5fce03d
(see PINNED_EXPORTER.txt and FORMAT.pinned.md). Exporter marks Android fields RESERVED;
this app defines the concrete producer shape below for review.

## Document `source` when produced on-phone

| Field | Value |
| --- | --- |
| `kind` | `android-ebur128` |
| `gainOrigin` | `measured-lufs` |
| `gainUnits` | `dB` |
| `referenceLufs` | `-18` (labeled ReplayGain-compatible reference used when computing `trackGainDb` from `measuredLufs`) |
| `analyzer` | `libebur128` |
| `analyzerVersion` | `1.2.6` |
| `producer` | `RetroMusicPlayer-custom` |
| `producerVersion` | app versionName-versionCode |
| `serverVersion` | omit |

## Track fields

| Field | Notes |
| --- | --- |
| `rootId` + `relativePath` | Primary match identity (exact). |
| `trackGainDb` | `referenceLufs - measuredLufs` when measured. Omit if unknown. Never write `0` to mean missing. |
| `albumGainDb` | Optional; preserved on re-export if imported. |
| `measuredLufs` | Integrated EBU R128 LUFS from libebur128. |
| `truePeakDbtp` | True peak dBTP when measured; required for positive boost at playback. |
| `source` | Optional provenance: kind/analyzer/analyzerVersion/measuredAt/gainOrigin. |
| `sourceItemId` | Optional on Android scans; preserved if imported from Jellyfin. |

## Jellyfin imports

`gainOrigin=lufs-or-replaygain-unspecified`. Do **not** derive `measuredLufs` from those gains or apply a universal target shift. Playback uses the exported `trackGainDb` / `albumGainDb` as-is (subject to strength / max boost-cut / peak safety).

## Collision

Producers claiming `collisionKeyAlgorithm=jf-ck-v1` must gate collisions.
Android NFC label: `runtime-android-Normalizer-NFC`.
