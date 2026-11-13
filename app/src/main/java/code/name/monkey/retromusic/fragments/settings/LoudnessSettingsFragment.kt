package code.name.monkey.retromusic.fragments.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import code.name.monkey.retromusic.BuildConfig
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.LoudnessDao
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.loudness.CollisionKey
import code.name.monkey.retromusic.loudness.JleAudioHash
import code.name.monkey.retromusic.loudness.LoudnessJson
import code.name.monkey.retromusic.loudness.LoudnessMatching
import code.name.monkey.retromusic.loudness.LoudnessRepository
import code.name.monkey.retromusic.loudness.LoudnessScanService
import code.name.monkey.retromusic.loudness.LoudnessSchedule
import code.name.monkey.retromusic.loudness.ParseResult
import code.name.monkey.retromusic.loudness.PathValidation
import code.name.monkey.retromusic.loudness.PhoneAudioHasher
import code.name.monkey.retromusic.preferences.EditableSeekBarPreference
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

class LoudnessSettingsFragment : AbsSettingsFragment() {
    private val loudnessDao: LoudnessDao by inject()
    private val repository by lazy { LoudnessRepository(loudnessDao) }
    private val hasher by lazy { PhoneAudioHasher(requireContext(), loudnessDao) }

    private var pendingDocumentJson: String? = null

    private val openImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }
    private val createExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportToUri(uri)
    }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) mapRoot(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_loudness)
        CollisionKey.ensureLoaded(requireContext())
    }

    override fun onPause() {
        commitTypedValues(preferenceScreen)
        super.onPause()
    }

    private fun commitTypedValues(group: PreferenceGroup?) {
        group ?: return
        for (i in 0 until group.preferenceCount) {
            when (val p = group.getPreference(i)) {
                is EditableSeekBarPreference -> p.commitPending()
                is PreferenceGroup -> commitTypedValues(p)
            }
        }
    }

    override fun invalidateSettings() {
        findPreference<Preference>("loudness_schedule_start")?.apply {
            summary = PreferenceUtil.loudnessScheduleStart
            setOnPreferenceClickListener {
                pickTime(PreferenceUtil.loudnessScheduleStart) { hhmm ->
                    val err = LoudnessSchedule.validateWindow(hhmm, PreferenceUtil.loudnessScheduleEnd)
                    if (err != null) {
                        showToast(R.string.loudness_equal_times_error)
                    } else {
                        PreferenceUtil.loudnessScheduleStart = hhmm
                        summary = hhmm
                    }
                }
                true
            }
        }
        findPreference<Preference>("loudness_schedule_end")?.apply {
            summary = PreferenceUtil.loudnessScheduleEnd
            setOnPreferenceClickListener {
                pickTime(PreferenceUtil.loudnessScheduleEnd) { hhmm ->
                    val err = LoudnessSchedule.validateWindow(PreferenceUtil.loudnessScheduleStart, hhmm)
                    if (err != null) {
                        showToast(R.string.loudness_equal_times_error)
                    } else {
                        PreferenceUtil.loudnessScheduleEnd = hhmm
                        summary = hhmm
                    }
                }
                true
            }
        }
        findPreference<Preference>("loudness_map_root")?.setOnPreferenceClickListener {
            openTree.launch(null)
            true
        }
        findPreference<Preference>("loudness_import")?.setOnPreferenceClickListener {
            openImport.launch(arrayOf("application/json", "text/*", "*/*"))
            true
        }
        findPreference<Preference>("loudness_export")?.setOnPreferenceClickListener {
            createExport.launch("loudness-export.json")
            true
        }
        findPreference<Preference>("loudness_scan")?.setOnPreferenceClickListener {
            val root = PreferenceUtil.loudnessRootUri
            if (root.isBlank() || !File(root).isDirectory) {
                showToast(R.string.pref_summary_loudness_map_root)
            } else if (LoudnessScanService.running) {
                showToast(R.string.loudness_job_already_running)
            } else {
                LoudnessScanService.start(requireContext(), root, PreferenceUtil.loudnessRootId)
            }
            true
        }
        findPreference<Preference>("loudness_hash_library")?.setOnPreferenceClickListener {
            if (LoudnessScanService.running) {
                showToast(R.string.loudness_job_already_running)
            } else {
                LoudnessScanService.startHashing(requireContext())
            }
            true
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = repository.count()
            val withHash = repository.countWithAudioHash()
            val (phoneHashed, phoneNoHash) = withContext(Dispatchers.IO) {
                loudnessDao.countPhoneHashed(JleAudioHash.ALGORITHM) to
                    loudnessDao.countPhoneNoHash(JleAudioHash.ALGORITHM)
            }
            findPreference<Preference>("loudness_status")?.summary =
                if (count == 0) getString(R.string.pref_summary_loudness_status)
                else "$count stored entries ($withHash with a song hash) · root=${PreferenceUtil.loudnessRootId} · path=${PreferenceUtil.loudnessRootUri.ifBlank { "(unset)" }}"
            findPreference<Preference>("loudness_hash_library")?.summary =
                getString(R.string.pref_summary_loudness_hash_library) +
                    "\nPhone files hashed: $phoneHashed · no hash possible: $phoneNoHash" +
                    (LoudnessScanService.lastHashSummary?.let { "\nLast run: $it" } ?: "")
        }
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        val mins = LoudnessSchedule.parseHhMm(current) ?: (22 * 60)
        TimePickerDialog(requireContext(), { _, h, m ->
            onPicked(LoudnessSchedule.formatHhMm(h * 60 + m))
        }, mins / 60, mins % 60, true).show()
    }

    private fun mapRoot(uri: Uri) {
        val take = Intent.FLAG_GRANT_READ_URI_PERMISSION
        requireContext().contentResolver.takePersistableUriPermission(uri, take)
        // Prefer filesystem path when available for MediaExtractor scanning.
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val maybePath = if (docId.contains(':')) {
            val parts = docId.split(':', limit = 2)
            if (parts[0].equals("primary", true)) "/storage/emulated/0/${parts[1]}" else null
        } else null
        PreferenceUtil.loudnessRootUri = maybePath ?: uri.toString()
        PreferenceUtil.loudnessRootId = PreferenceUtil.loudnessRootId.ifBlank { "music" }
        findPreference<Preference>("loudness_map_root")?.summary = PreferenceUtil.loudnessRootUri
        refreshStatus()
    }

    private fun importFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: return@launch
            withContext(Dispatchers.Default) { CollisionKey.ensureLoaded(requireContext()) }
            val parsed = withContext(Dispatchers.Default) {
                LoudnessJson.parse(text.reader())
            }
            when (parsed) {
                is ParseResult.Error -> showToast(parsed.message)
                is ParseResult.Ok -> {
                    val doc = parsed.document
                    val rootPath = PreferenceUtil.loudnessRootUri
                    val rootDir = rootPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
                    val (summary, phoneHashed) = withContext(Dispatchers.IO) {
                        val phoneCounts = loudnessDao.phoneHashCounts(JleAudioHash.ALGORITHM)
                            .associate { it.audioSha256 to it.files }
                        val pathExists: ((code.name.monkey.retromusic.loudness.TrackEntry) -> Boolean)? =
                            rootDir?.let { dir -> { t -> File(dir, t.relativePath).isFile } }
                        LoudnessMatching.summarize(doc, phoneCounts, pathExists) to
                            loudnessDao.countPhoneHashed(JleAudioHash.ALGORITHM)
                    }
                    val msg = buildString {
                        append("tracks=${doc.tracks.size}\n")
                        append("roots=${doc.roots.joinToString { it.id }}\n")
                        append("source=${doc.source.kind}/${doc.source.gainOrigin}\n")
                        when (summary.hashStatus) {
                            LoudnessMatching.HashStatus.USED ->
                                append("song hashes: ${JleAudioHash.ALGORITHM}\n")
                            LoudnessMatching.HashStatus.ABSENT ->
                                append("song hashes: none in this file (paths only)\n")
                            LoudnessMatching.HashStatus.UNKNOWN_ALGORITHM ->
                                append("song hashes: unknown algorithm \"${summary.algorithm}\", ignored; using paths only\n")
                        }
                        append("matched_by_hash=${summary.matchedByHash}")
                        if (summary.phoneFilesMatchedByHash != summary.matchedByHash) {
                            append(" (${summary.phoneFilesMatchedByHash} phone files)")
                        }
                        append("\n")
                        append("matched_by_path=${summary.matchedByPath}")
                        if (!summary.pathCheckPossible) append(" (no folder root mapped)")
                        append("\n")
                        append("unmatched=${summary.unmatched}\n")
                        if (summary.hashStatus == LoudnessMatching.HashStatus.USED) {
                            append("hashUnavailable=${summary.hashUnavailable}\n")
                        }
                        if (summary.ignoredInvalidHashes > 0) {
                            append("invalid_hashes_ignored=${summary.ignoredInvalidHashes}\n")
                        }
                        append("phone_files_hashed=$phoneHashed")
                        if (phoneHashed == 0 && summary.hashStatus == LoudnessMatching.HashStatus.USED) {
                            append(" (run \"Hash phone library\" for hash matches)")
                        }
                        append("\n")
                        append("coverage.collisions=${doc.coverage?.collisions ?: 0}\n")
                        append("Applying replaces all stored loudness rows.")
                    }
                    pendingDocumentJson = text
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.loudness_import_preview_title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.loudness_import_apply) { _, _ ->
                            applyPendingImport()
                        }
                        .setNegativeButton(R.string.loudness_import_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun applyPendingImport() {
        val text = pendingDocumentJson ?: return
        pendingDocumentJson = null
        viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) { LoudnessJson.parse(text.reader()) }
            if (parsed is ParseResult.Ok) {
                val n = repository.importDocument(parsed.document)
                showToast("Imported $n tracks")
                refreshStatus()
            }
        }
    }

    private fun exportToUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            CollisionKey.ensureLoaded(requireContext())
            showToast(R.string.loudness_export_hashing)
            val rootId = PreferenceUtil.loudnessRootId
            val rootDir = PreferenceUtil.loudnessRootUri.takeIf { it.isNotBlank() }
                ?.let { File(it) }?.takeIf { it.isDirectory }
            val doc = repository.exportDocument(
                producerVersion = "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}",
                // Hash of the phone's own file at root + relativePath (cached, computed if missing).
                audioHashFor = { e ->
                    if (rootDir != null && e.rootId == rootId) {
                        hasher.hashForFile(File(rootDir, e.relativePath), computeIfMissing = true)
                    } else null
                },
            )
            val json = withContext(Dispatchers.Default) { LoudnessJson.write(doc) }
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter().use { w ->
                    w?.write(json)
                }
            }
            val hashed = doc.tracks.count { it.audioSha256 != null }
            showToast("Exported ${doc.tracks.size} tracks ($hashed with a song hash)")
        }
    }
}
