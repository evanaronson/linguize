package com.evanaronson.linguize.ui.popup

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.home.HomeActivity

/** Opens the app separately and restores its service-hosted popup when the app closes. */
class PopupAppActivity : ComponentActivity() {
    private val launch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            launch.launch(if (intent.getBooleanExtra(OPEN_SETTINGS, false)) {
                HomeActivity.settingsIntent(this)
            } else HomeActivity.homeIntent(this))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_linguize_failed, Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.open_linguize_failed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A failed app launch can finish this gateway before its first resume,
        // so onPause is not guaranteed. Rotation must keep waiting for the same result.
        if (isFinishing) PopupReturn.left(intent.getLongExtra(RETURN_TOKEN, 0L))
    }

    companion object {
        private const val RETURN_TOKEN = "returnToken"
        private const val OPEN_SETTINGS = "openSettings"
        fun intent(context: Context, returnToken: Long, openSettings: Boolean = false) = Intent(context, PopupAppActivity::class.java)
            .putExtra(RETURN_TOKEN, returnToken)
            .putExtra(OPEN_SETTINGS, openSettings)
            // Its own temporary task leaves the source app and popup untouched.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
