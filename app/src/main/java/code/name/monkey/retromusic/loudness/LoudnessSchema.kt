package code.name.monkey.retromusic.loudness

/**
 * Portable loudness export schema (schemaVersion 1), pinned against
 * oiuy-retro/jellyfin-loudness-exporter@952392ae3c7e6e2eb2f976eb9cd7d51ea5fce03d.
 *
 * Android producer fields below are this app's concrete extension. The exporter
 * document marks them RESERVED; parent/teammate may later freeze matching names.
 */
object LoudnessSchema {
    const val FORMAT = "jellyfin-loudness-export"
    const val SCHEMA_VERSION = 1
    const val COLLISION_ALGORITHM = "jf-ck-v1"
    const val CASE_FOLD_SPEC = "unicode-15.1-full-C+F-non-Turkic"
    const val NFC_SPEC_ANDROID = "runtime-android-Normalizer-NFC"
    const val ROOT_ID_SPEC = "ordinal-exact"

    const val KIND_JELLYFIN = "jellyfin-api"
    const val KIND_ANDROID = "android-ebur128"
    const val GAIN_ORIGIN_JELLYFIN = "lufs-or-replaygain-unspecified"
    const val GAIN_ORIGIN_MEASURED = "measured-lufs"
    const val GAIN_UNITS_DB = "dB"

    /** Informational ReplayGain-compatible reference used when we compute gain from measured LUFS. */
    const val REFERENCE_LUFS = -18.0

    const val ANALYZER_LIBEBUR128 = "libebur128"
    const val ANALYZER_VERSION = "1.2.6"
    const val PRODUCER_NAME = "RetroMusicPlayer-custom"

    /** Gains outside this range are still accepted but counted as outOfRange. */
    const val OUT_OF_RANGE_ABS = 30.0
}
