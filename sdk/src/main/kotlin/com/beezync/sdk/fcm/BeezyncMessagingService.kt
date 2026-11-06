package com.beezync.sdk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

open class BeezyncMessagingService : FirebaseMessagingService() {
    protected open val channelId: String get() = BeezyncNotifications.DEFAULT_CHANNEL_ID

    protected open val smallIcon: Int get() = applicationInfo.icon

    override fun onNewToken(token: String) {
        val sdk = Beezync.shared ?: return
        runBlocking {
            withTimeoutOrNull(NETWORK_BUDGET_MS) { sdk.registerDevice(token, sdk.externalUserId, sdk.platform) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.beezyncId()
        val sdk = Beezync.shared
        if (sdk != null && id != null) {
            runBlocking { withTimeoutOrNull(NETWORK_BUDGET_MS) { sdk.trackReceived(id) } }
        }
        val title = message.notification?.title ?: message.data["title"]
        val body = message.notification?.body ?: message.data["body"]
        if (title != null || body != null) show(id, title, body, message.data)
    }

    protected open fun show(id: String?, title: String?, body: String?, payload: Map<String, String>) {
        if (!BeezyncNotifications.canPost(this)) return
        BeezyncNotifications.ensureChannel(this, channelId)
        val requestCode = (id ?: System.nanoTime().toString()).hashCode()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            payload.forEach { (k, v) -> putExtra(k, v) }
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, requestCode, launch, flags))
        if (id != null) {
            val dismiss = Intent(this, BeezyncDismissReceiver::class.java).putExtra(BeezyncPush.DATA_KEY, id)
            builder.setDeleteIntent(PendingIntent.getBroadcast(this, requestCode, dismiss, flags))
        }
        try {
            NotificationManagerCompat.from(this).notify(requestCode, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val NETWORK_BUDGET_MS = 10_000L
    }
}

class BeezyncDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.beezyncId() ?: return
        val sdk = Beezync.shared ?: return
        val pending = goAsync()
        Thread {
            try {
                runBlocking { withTimeoutOrNull(8_000L) { sdk.trackDismissed(id) } }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
