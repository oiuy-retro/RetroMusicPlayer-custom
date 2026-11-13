# Android producer fields (schemaVersion 1 extension)

Pinned importer reference: oiuy-retro/jellyfin-loudness-exporter@952392ae3c7e6e2eb2f976eb9cd7d51ea5fce03d
(see PINNED_EXPORTER.txt and FORMAT.pinned.md).

**Status:** Compatible **additive RESERVED** extension — **not frozen**. Exporter does not
emit these fields; consumers must ignore unknown additive fields. This document is the
app’s concrete proposal for review, not a wire break of the shared v1 contract.

## Primary matching identity

Consumers match by **ordinal-exact `rootId` + the exact original `relativePath`**
(case and Unicode form preserved after separator conversion and root stripping).
Never match on basename alone. Never guess via path-suffix fallback.

## Collision validation (`jf-ck-v1`)

Collision keys are **validation only**, not playback identity.
Algorithm: `rootId` (ordinal exact) + `U+001F` + `FullCaseFold_Unicode15.1_C+F(NFC_runtime(relativePath))`.
No NFC after folding. Android NFC label: `runtime-android-Normalizer-NFC` (runtime, not a pinned UCD NFC table).
Case fold: pinned Unicode 15.1.0 status **C+F only** (non-Turkic). Producers claiming
`collisionKeyAlgorithm=jf-ck-v1` must gate/omit colliding groups.

## Document `source` when produced on-phone

| Field | Value |
| --- | --- |
| `kind` | `android-ebur128` |
| `gainOrigin` | `measured-lufs` |
| `gainUnits` | `dB` |
| `referenceLufs` | `-18` when gains are computed from measured LUFS (Android-labeled). **Distinct from** Jellyfin’s optional informational `lufsDerivedReferenceLufs` — do not emit `lufsDerivedReferenceLufs` on Android-produced documents; omit Jellyfin’s `referenceLufs` confusion by only using `referenceLufs` here. |
| `analyzer` | `libebur128` |
| `analyzerVersion` | `1.2.6` |
| `producer` | `RetroMusicPlayer-custom` |
| `producerVersion` | app versionName-versionCode |
| `serverVersion` | omit |

## Track fields

| Field | Notes |
| --- | --- |
| `rootId` + `relativePath` | Exact primary match identity. |
| `trackGainDb` | `referenceLufs - measuredLufs` when measured. Omit if unknown. Never write `0` to mean missing. |
| `albumGainDb` | Optional; **preserved on re-export** if imported. Does **not** imply this app implements album LUFS scanning. |
| `measuredLufs` | Integrated EBU R128 LUFS from libebur128 when scanned. |
| `truePeakDbtp` | Optional schema field when measured. Playback boost safety is a **separate policy** (this build is attenuation-only; true peak alone does not authorize boost with EQ/crossfade). |
| `source` | Optional per-track provenance: kind/analyzer/analyzerVersion/measuredAt/gainOrigin. |
| `sourceItemId` | Optional on Android scans; preserved if imported from Jellyfin. |

## Coverage / partial / exported-only denominators

Follow shared FORMAT: `entriesExported` is the length of `tracks` (denominator for
exported-only missing-gain stats). `missingTrackGain` / `missingAlbumGain` /
`missingBothGains` count **only among final exported tracks**. `partial` is true only
for explicitly partial producer runs. Colliding members claiming `jf-ck-v1` are omitted
from `tracks` and counted in `collisions`.

## Jellyfin imports

`gainOrigin=lufs-or-replaygain-unspecified`. Do **not** derive `measuredLufs` from those
gains or apply a universal target shift. Playback uses exported `trackGainDb` /
`albumGainDb` as-is (subject to strength / max cut; positive boost disabled).

## Audit note

This extension remains **explicitly not frozen** until the exporter teammate freezes
matching names in FORMAT.md. Until then, treat as additive/reserved.
