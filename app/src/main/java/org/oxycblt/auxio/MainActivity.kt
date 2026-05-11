/*
 * Copyright (c) 2021 Fluxio Project
 * MainActivity.kt is part of Fluxio.
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
package org.oxycblt.auxio

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.databinding.ActivityMainBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.systemBarInsetsCompat
import timber.log.Timber as L

/**
 * Auxio's single [AppCompatActivity].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val playbackModel: PlaybackViewModel by viewModels()
    @Inject lateinit var uiSettings: UISettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupTheme()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        L.d("Activity created")
    }

    override fun onResume() {
        super.onResume()

        startService(
            Intent(this, AuxioService::class.java)
                .setAction(AuxioService.ACTION_START)
                .putExtra(AuxioService.INTENT_KEY_START_ID, IntegerTable.START_ID_ACTIVITY)
        )

        if (!startIntentAction(intent)) {
            playbackModel.playDeferred(DeferredPlayback.RestoreState(false))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startIntentAction(intent)
    }

    private fun setupTheme() {
        AppCompatDelegate.setDefaultNightMode(uiSettings.theme)
        // Apply pure-black theme for OLED when in dark mode; standard Fluxio theme otherwise.
        // setTheme() must point to Theme_Fluxio_Base (not Theme_Fluxio) so that the full
        // version chain (V23→V27→V29→V31→Base) is resolved, applying Fluxio colors,
        // the correct toolbar style, and windowNoTitle/windowActionBar=false.
        if (isNight && uiSettings.useBlackTheme) {
            L.d("Applying pure-black theme")
            setTheme(R.style.Theme_Fluxio_PureBlack)
        } else {
            L.d("Applying standard theme")
            setTheme(R.style.Theme_Fluxio_Base)
        }
    }

    private fun setupEdgeToEdge(contentView: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        contentView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    private fun startIntentAction(intent: Intent?): Boolean {
        if (intent == null) {
            L.d("No intent to handle")
            return false
        }

        if (intent.getBooleanExtra(KEY_INTENT_USED, false)) {
            L.d("Already used this intent")
            return true
        }
        intent.putExtra(KEY_INTENT_USED, true)

        val action =
            when (intent.action) {
                Intent.ACTION_VIEW -> DeferredPlayback.Open(intent.data ?: return false)
                Auxio.INTENT_KEY_SHORTCUT_SHUFFLE -> DeferredPlayback.ShuffleAll
                else -> {
                    L.w("Unexpected intent ${intent.action}")
                    return false
                }
            }
        L.d("Translated intent to $action")
        playbackModel.playDeferred(action)
        return true
    }

    private companion object {
        const val KEY_INTENT_USED = BuildConfig.APPLICATION_ID + ".key.FILE_INTENT_USED"
    }
}
