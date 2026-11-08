package com.evanaronson.linguize.ui.writingpad

import android.content.Context
import android.content.Intent

/**
 * Opens the pad's activity (Write's "Writing pad" row). It is an entry point, so it lives in
 * `menu`, which `ui` must not import; the class is named here instead, and a test holds the
 * name to the manifest.
 */
fun writingPadIntent(context: Context): Intent = Intent().setClassName(context, WRITING_PAD_ACTIVITY)

internal const val WRITING_PAD_ACTIVITY = "com.evanaronson.linguize.menu.WritingPadActivity"
