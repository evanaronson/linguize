package com.evanaronson.linguize.accessibility

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager

/** Accessibility overlays can cover the keyguard, so they must explicitly hide while locked. */
internal class UnlockedScreen(
    private val context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var screenOff = false
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Screen-off must hide immediately, even before keyguard/power state catches up.
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            refresh()
        }
    }

    fun start() {
        check(!registered)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        refresh()
    }

    fun refresh() {
        onChanged(!screenOff && power.isInteractive && !keyguard.isKeyguardLocked)
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}
