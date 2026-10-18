package ai.agentshelf.artifactmcp.notify

import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.bridge.ArtifactNotifier
import ai.agentshelf.artifactmcp.config.OriginPolicy
import ai.agentshelf.artifactmcp.ui.MainActivity
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object Notifications {
    const val CHANNEL_LOCATION = "location"
    const val CHANNEL_ARTIFACT = "artifact"
    const val LOCATION_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOCATION, context.getString(R.string.channel_location), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_location_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ARTIFACT, context.getString(R.string.channel_artifact), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_artifact_description) },
        )
    }

    /** Explicit intent that opens the viewer at `<origin>/<artifactId>`. */
    fun viewerIntent(context: Context, origin: String, artifactId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(OriginPolicy.viewerUrl(origin, artifactId).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Stable notification id per artifact request, so a repeated request replaces its notification. */
    fun notificationId(artifactId: String, requestId: String): Int = 0x4000_0000 or ((artifactId + "\u0000" + requestId).hashCode() and 0x3FFF_FFFF)

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** `notify.show`: a local notification attributed to the artifact; a tap opens its viewer. */
class SystemArtifactNotifier(
    private val context: Context,
    private val origin: () -> String?,
) : ArtifactNotifier {
    @SuppressLint("MissingPermission") // Notifications.canPost() checks POST_NOTIFICATIONS
    override fun show(artifactId: String, label: String, requestId: String, title: String, body: String): Boolean {
        val currentOrigin = origin() ?: return false
        if (!Notifications.canPost(context)) return false
        val id = Notifications.notificationId(artifactId, requestId)
        val tap = PendingIntent.getActivity(
            context,
            id,
            Notifications.viewerIntent(context, currentOrigin, artifactId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ARTIFACT)
            .setSmallIcon(R.drawable.ic_stat_artifact)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(label)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup("artifact:$artifactId")
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
