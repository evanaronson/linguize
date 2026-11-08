package com.evanaronson.linguize.menu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.AppNavigation
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.photo.PhotoScreen
import com.evanaronson.linguize.ui.photo.PhotoState
import com.evanaronson.linguize.ui.photo.PhotoViewModel
import com.evanaronson.linguize.ui.photo.understand
import com.evanaronson.linguize.ui.theme.AppTheme

/**
 * The share-sheet target for images (ACTION_SEND, image/\*): reads the shared photo with the
 * configured provider and shows the result on its own screen. The image is opened once,
 * shrunk in memory and never stored.
 */
class PhotoActivity : ComponentActivity() {
    private val photo: PhotoViewModel by viewModels { PhotoViewModel.factory(App.of(this)) }
    private var returningFromSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        returningFromSettings = savedInstanceState?.getBoolean(KEY_RETURNING) == true
        // The view model keeps the result across rotation; only a fresh launch reads the intent.
        if (savedInstanceState == null && photo.state == PhotoState.Idle) {
            val uri = SharedContent.image(intent, packageName)
            when {
                uri == null -> Unit
                // Exported: any app can send images here without a tap, so each gets only a few a minute.
                !limit.tryAcquire(callingPackage) -> photo.limited()
                else -> photo.understand(this, uri)
            }
        }
        setContent {
            AppTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        AppNavigation(
                            title = stringResource(R.string.lab_share_photo_title),
                            onBack = ::finish,
                            backLabel = stringResource(R.string.app_name),
                            onOpenSettings = null,
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        PhotoScreen(photo, onOpenSettings = ::openSettings)
                    }
                }
            }
        }
    }

    private fun openSettings() {
        returningFromSettings = true
        startActivity(HomeActivity.settingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onResume() {
        super.onResume()
        if (returningFromSettings) {
            returningFromSettings = false
            photo.recheckIfStale()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RETURNING, returningFromSettings)
    }

    private companion object {
        const val KEY_RETURNING = "returningFromSettings"

        /** Photos cost more than a check, so fewer: 3 per app and 6 in all each minute. */
        val limit = CheckRateLimit(perApp = 3, overall = 6)
    }
}
