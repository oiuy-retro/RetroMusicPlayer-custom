package code.name.monkey.retromusic.loudness

/**
 * Import-preview counts for song-hash matching (pure, unit-tested).
 *
 * Order per track: audioSha256 match against the phone's hash cache first, then the
 * exact rootId + relativePath file check as the fallback. Hashes are used only when
 * the document's audioHashAlgorithm is exactly jle-audio-v1.
 */
object LoudnessMatching {
    enum class HashStatus { USED, ABSENT, UNKNOWN_ALGORITHM }

    data class Summary(
        val hashStatus: HashStatus,
        /** Algorithm string from the file (for UNKNOWN_ALGORITHM wording). */
        val algorithm: String?,
        val totalTracks: Int,
        val matchedByHash: Int,
        /** Phone files covered by hash matches (byte-identical duplicates each count). */
        val phoneFilesMatchedByHash: Int,
        val matchedByPath: Int,
        val unmatched: Int,
        /** Tracks in the file with no audioSha256 (only counted when hashes are usable). */
        val hashUnavailable: Int,
        val ignoredInvalidHashes: Int,
        /** False when no filesystem root is set, so path matches could not be checked. */
        val pathCheckPossible: Boolean,
    )

    /**
     * @param phoneHashFiles phone jle-audio-v1 hash -> number of phone files with it.
     * @param pathExists exact-path check for a track, or null when no filesystem root is mapped.
     */
    fun summarize(
        document: LoudnessDocument,
        phoneHashFiles: Map<String, Int>,
        pathExists: ((TrackEntry) -> Boolean)?,
    ): Summary {
        val status = when {
            document.audioHashAlgorithm == null -> HashStatus.ABSENT
            document.audioHashesUsable -> HashStatus.USED
            else -> HashStatus.UNKNOWN_ALGORITHM
        }
        val useHashes = status == HashStatus.USED
        var byHash = 0
        var byPath = 0
        var unmatched = 0
        var hashUnavailable = 0
        val matchedHashes = HashSet<String>()
        for (t in document.tracks) {
            val h = if (useHashes) t.audioSha256 else null
            if (useHashes && h == null) hashUnavailable++
            if (h != null && (phoneHashFiles[h] ?: 0) > 0) {
                byHash++
                matchedHashes.add(h)
            } else if (pathExists != null && pathExists(t)) {
                byPath++
            } else {
                unmatched++
            }
        }
        return Summary(
            hashStatus = status,
            algorithm = document.audioHashAlgorithm,
            totalTracks = document.tracks.size,
            matchedByHash = byHash,
            phoneFilesMatchedByHash = matchedHashes.sumOf { phoneHashFiles[it] ?: 0 },
            matchedByPath = byPath,
            unmatched = unmatched,
            hashUnavailable = hashUnavailable,
            ignoredInvalidHashes = document.ignoredInvalidAudioHashes,
            pathCheckPossible = pathExists != null,
        )
    }
}
