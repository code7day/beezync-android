package com.beezync.sdk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

object BeezyncNotifications {
    const val DEFAULT_CHANNEL_ID = "beezync_default"

    fun ensureChannel(
        context: Context,
        id: String = DEFAULT_CHANNEL_ID,
        name: String = "Notifications",
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

fun Beezync.Companion.initialize(context: Context, config: BeezyncConfig): Beezync {
    BeezyncNotifications.ensureChannel(context)
    return init(config, SharedPrefsStore(context))
}

fun RemoteMessage.beezyncId(): String? = BeezyncPush.notificationId(data)

fun Intent.beezyncId(): String? = getStringExtra(BeezyncPush.DATA_KEY)?.takeIf { it.isNotBlank() }

fun Beezync.reportNotificationOpened(intent: Intent): String? {
    val id = intent.beezyncId() ?: return null
    intent.removeExtra(BeezyncPush.DATA_KEY)
    scope.launch {
        trackReceived(id)
        trackClicked(id)
    }
    return id
}
