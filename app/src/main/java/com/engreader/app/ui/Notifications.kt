package com.engreader.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Asks for the notification permission the playback notification needs, once.
 *
 * Only from Android 13, and only when it has not already been granted: below that the
 * permission does not exist, and a granted one would prompt for nothing. A refusal is
 * not an error — the voice is unaffected, the listener just cannot stop it from the
 * notification shade.
 *
 * Shared by every screen that can start playback, because the notification is what keeps
 * the voice going with the screen off and each of them can be the first to need it.
 */
fun askForNotificationPermission(context: Context, launch: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) launch(Manifest.permission.POST_NOTIFICATIONS)
}
