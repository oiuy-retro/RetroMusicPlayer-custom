# Song-hash matching (jle-audio-v1)

## What it is

A loudness file from the Windows exporter can carry an `audioSha256` for each track. It is a
SHA-256 of the audio part of the file only. Tags, cover art, padding and tag frames are left
out, so the hash stays the same when a file is moved, renamed or retagged. The rules are the
exporter's `jle-audio-v1` algorithm, pinned at
https://github.com/oiuy-retro/jellyfin-loudness-exporter/blob/6af8e0c8d4f2e6e011369daaf81aaf80e7dbeefc/docs/FORMAT.md#audio-hash-jle-audio-v1

The file format stays schemaVersion 1. The fields are optional additions:

- top level `audioHashAlgorithm`: `"jle-audio-v1"`
- track `audioSha256`: 64 lowercase hex characters
- `coverage.hashed` and `coverage.hashUnavailable`

`relativePath` is still required. Unknown fields are ignored.

## How the phone uses it

1. Settings → Loudness → **Hash phone library** reads every song in the library once and stores
   its hash, together with the file's size and modified time. Later runs read only files whose
   size or modified time changed. The job runs as a foreground notification with progress and a
   Stop button. Files are read directly, or through Android's content resolver when direct
   access fails or the song is a content:// item, so hashing needs no mapped folder.
2. When a song plays, the app looks up its hash. If it was not hashed yet and the stored loudness
   data contains hashes, it hashes that one file first and caches it.
3. Matching order: a stored track whose `audioSha256` equals the song's hash; otherwise the exact
   `rootId` + `relativePath` under the mapped music folder. If several phone files have the same
   hash (byte-identical copies), all of them get that track's gain.
4. Hashes are used only when `audioHashAlgorithm` is exactly `jle-audio-v1`. With another value
   the import preview says the algorithm is unknown and only paths are used. A track value that
   is not 64 lowercase hex characters is ignored and counted in the preview.

## Import preview numbers

- `matched_by_hash`: tracks in the file whose hash is on the phone (in parentheses: how many phone
  files that covers, when there are duplicates).
- `matched_by_path`: tracks not matched by hash whose exact path exists under the mapped folder.
- `unmatched`: neither.
- `hashUnavailable`: tracks in the file without a hash (the exporter could not hash them).
- `phone_files_hashed`: how many phone files have a cached hash. If this is 0, run
  **Hash phone library** first, then import again.

## Export

Export writes `audioHashAlgorithm` and, for each stored track whose file exists under the mapped
music folder, the phone's own hash of that file (computed if needed). Tracks without a hash are
counted in `coverage.hashUnavailable`.

## Tests

`JleAudioHashTest` runs every entry of the exporter's `vectors.json` against the fixture clips
in `app/src/test/resources/jle-audio/` (copied from the pinned commit; short generated tones).
The Kotlin code was also compared with the exporter's Python reference on about 2,300 randomly
cut or damaged copies of those clips, with identical results.

## Limits

- Different rips or encodes of the same song have different hashes; they fall back to paths.
- MP3Gain-style tools that rewrite the audio frames change the hash.
- Supported: FLAC, MP3, MP4/M4A, Ogg Vorbis, Opus, WAV. Others (WMA, AIFF, APE, WavPack, raw
  AAC, chained Ogg) get no hash and use path matching.
- The first hash run reads the whole library and can take a long time on a large collection.
- A song is matched by hash only after it has been hashed; the first play of an unhashed song
  waits for that one file to be read before the gain is applied.
