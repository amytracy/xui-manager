package dev.autorasqr.xuimanager.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private var secureRequests = 0

/**
 * Sets FLAG_SECURE while the calling composable is on screen: no screenshots, no recording and a blank
 * preview in Recents. Reference-counted so nested secure content doesn't clear it early.
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        secureRequests++
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secureRequests--
            if (secureRequests <= 0) {
                secureRequests = 0
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
