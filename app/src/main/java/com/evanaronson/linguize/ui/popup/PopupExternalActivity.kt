package com.evanaronson.linguize.ui.popup

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
            Toast.makeText(this, "Couldn't open that. No app can handle it.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) SettingsReturn.left()
    }

    companion object {
        private const val EXTERNAL = "external"
        fun intent(context: Context, external: Intent) = Intent(context, PopupExternalActivity::class.java)
            .putExtra(EXTERNAL, external)
            // Its own temporary task: no Home screen is inserted into the popup's detour.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
