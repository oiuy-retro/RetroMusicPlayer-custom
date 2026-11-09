package code.name.monkey.retromusic.loudness

object PathValidation {
    private val ABSOLUTE = Regex("""^([A-Za-z]:[/\\]|[/\\]|\\\\)""")

    fun normalizeSeparators(path: String): String = path.replace('\\', '/')

    /** Returns null if valid; otherwise an error code. */
    fun validateRelative(path: String): String? {
        if (path.isEmpty()) return "empty_relative_path"
        if (path.indexOf('\u0000') >= 0) return "nul_in_path"
        if (path.startsWith('/')) return "leading_slash"
        if (ABSOLUTE.containsMatchIn(path)) return "absolute_path"
        val parts = path.split('/')
        if (parts.any { it.isEmpty() }) return "empty_segment"
        if (parts.any { it == "." || it == ".." }) return "dot_segment"
        return null
    }

    fun stripRootPrefix(absolutePath: String, rootAbsolute: String): String? {
        val file = normalizeSeparators(absolutePath)
        val root = normalizeSeparators(rootAbsolute).trimEnd('/')
        if (!file.startsWith(root, ignoreCase = false)) {
            // Try ordinal exact first; also allow case-insensitive FS match for Android roots.
            if (!file.startsWith(root, ignoreCase = true)) return null
            val stripped = file.substring(root.length).trimStart('/')
            return stripped.takeIf { validateRelative(it) == null }
        }
        val stripped = file.substring(root.length).trimStart('/')
        return stripped.takeIf { validateRelative(it) == null }
    }
}
