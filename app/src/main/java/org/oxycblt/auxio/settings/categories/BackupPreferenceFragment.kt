/*
 * Copyright (c) 2026 Fluxio Project
 * BackupPreferenceFragment.kt is part of Fluxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.settings.categories

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.backup.BackupManager
import org.oxycblt.auxio.backup.BackupResult
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference

/** Backup and restore settings screen. */
@AndroidEntryPoint
class BackupPreferenceFragment : BasePreferenceFragment(R.xml.preferences_backup) {

    @Inject lateinit var backupManager: BackupManager

    private var createFileLauncher: ActivityResultLauncher<String>? = null
    private var openFileLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createFileLauncher =
            registerForActivityResult(
                ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
            ) { uri ->
                if (uri != null) doExport(uri)
            }

        openFileLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) doImport(uri)
            }
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        // No dialog preferences on this screen.
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            getString(R.string.set_key_backup_export) -> {
                launchExport()
                true
            }
            getString(R.string.set_key_backup_import) -> {
                launchImport()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun launchExport() {
        createFileLauncher?.launch(BackupManager.DEFAULT_FILE_NAME)
    }

    private fun doExport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { backupManager.export(uri) }
            when (result) {
                is BackupResult.Success ->
                    showSnackbar(getString(R.string.lng_backup_exported, result.count))
                is BackupResult.Error ->
                    showSnackbar(getString(R.string.err_backup_export_failed, result.reason))
            }
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private fun launchImport() {
        openFileLauncher?.launch(arrayOf(BackupManager.MIME_TYPE, "application/octet-stream"))
    }

    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { backupManager.import(uri) }
            when (result) {
                is BackupResult.Success ->
                    showSnackbar(getString(R.string.lng_backup_imported, result.count))
                is BackupResult.Error ->
                    showSnackbar(getString(R.string.err_backup_import_failed, result.reason))
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }
}
