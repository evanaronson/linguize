package com.evanaronson.linguize.menu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.evanaronson.linguize.App
import com.evanaronson.linguize.ui.home.HomeActivity
import com.evanaronson.linguize.ui.theme.AppTheme
import com.evanaronson.linguize.ui.writingpad.WritingPadApp
import com.evanaronson.linguize.ui.writingpad.WritingPadViewModel

/** The Writing pad, full screen: drafts, and an editor checked as the writer pauses. Opened from Home. */
class WritingPadActivity : ComponentActivity() {
    private val pad: WritingPadViewModel by viewModels { WritingPadViewModel.factory(App.of(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                WritingPadApp(pad, onExit = ::finish, onOpenSettings = {
                    startActivity(HomeActivity.settingsIntent(this))
                })
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Autosave waits for a pause in typing; leaving the app shouldn't lose that last bit.
        pad.flush()
    }
}
