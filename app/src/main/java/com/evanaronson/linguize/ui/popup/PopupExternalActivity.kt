package com.evanaronson.linguize.ui.popup

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.home.SettingsReturn

/** Gives a service-hosted popup an Activity return path for browsers, Android settings and sharing. */
class PopupExternalActivity : ComponentActivity() {
    private val launch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        val external = intent.getParcelableExtra<Intent>(EXTERNAL)
        if (external == null) return finish()
        try {
            launch.launch(external.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.external_app_unavailable, Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.external_app_unavailable, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A missing/blocked external app can finish this gateway before its first resume,
        // so onPause is not guaranteed. Rotation must keep waiting for the same result.
        if (isFinishing) SettingsReturn.left(intent.getLongExtra(RETURN_TOKEN, 0L))
    }

    companion object {
        private const val EXTERNAL = "external"
        private const val RETURN_TOKEN = "returnToken"
        fun intent(context: Context, external: Intent, returnToken: Long) = Intent(context, PopupExternalActivity::class.java)
            .putExtra(EXTERNAL, external)
            .putExtra(RETURN_TOKEN, returnToken)
            // Its own temporary task: no Home screen is inserted into the popup's detour.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
