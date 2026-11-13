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

    /**
     * Strip an absolute filesystem root using **ordinal-exact** prefix and a path boundary.
     * `root` must match exactly (case-sensitive) and be followed by `/` (not a longer name prefix).
     * Returns the preserved relative path, or null if not under that root.
     */
    fun stripRootPrefix(absolutePath: String, rootAbsolute: String): String? {
        if (rootAbsolute.isBlank()) return null
        val file = normalizeSeparators(absolutePath)
        val root = normalizeSeparators(rootAbsolute).trimEnd('/')
        if (root.isEmpty()) return null
        if (file == root) return null // directory itself, not a file under it
        val prefix = "$root/"
        if (!file.startsWith(prefix)) return null // ordinal exact + boundary
        val stripped = file.substring(prefix.length)
        return stripped.takeIf { validateRelative(it) == null }
    }
}
