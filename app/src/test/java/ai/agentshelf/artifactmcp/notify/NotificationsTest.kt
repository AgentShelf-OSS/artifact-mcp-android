package ai.agentshelf.artifactmcp.notify

import ai.agentshelf.artifactmcp.ui.MainActivity
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationsTest {
    private val origin = "https://artifact.example.com"
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun `viewer intent targets the main activity at the artifact url`() {
        val intent = Notifications.viewerIntent(context, origin, "trip_1")
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.component?.packageName).isEqualTo(context.packageName)
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("$origin/trip_1")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `viewer intent rejects invalid artifact ids`() {
        Notifications.viewerIntent(context, origin, "a/b")
    }

    @Test fun `notify show posts an attributed notification that opens the viewer`() {
        Notifications.createChannels(context)
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifier = SystemArtifactNotifier(context) { origin }
        assertThat(notifier.show("trip_1", "Road trip", "n1", "Fuel stop", "Next fuel in 20 km")).isTrue()

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
        assertThat(posted).hasSize(1)
        val notification = posted.single()
        assertThat(notification.channelId).isEqualTo(Notifications.CHANNEL_ARTIFACT)
        assertThat(notification.extras.getString("android.title")).isEqualTo("Fuel stop")
        assertThat(notification.extras.getString("android.subText")).isEqualTo("Road trip")

        val tap = shadowOf(notification.contentIntent).savedIntent
        assertThat(tap.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(tap.dataString).isEqualTo("$origin/trip_1")
        assertThat(shadowOf(notification.contentIntent).isActivityIntent).isTrue()
        assertThat(shadowOf(notification.contentIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
    }

    @Test fun `notification ids are stable per request`() {
        assertThat(Notifications.notificationId("a", "r")).isEqualTo(Notifications.notificationId("a", "r"))
        assertThat(Notifications.notificationId("a", "r")).isNotEqualTo(Notifications.notificationId("a", "s"))
    }

    @Test fun `nothing is posted without an origin`() {
        val notifier = SystemArtifactNotifier(context) { null }
        assertThat(notifier.show("trip_1", "Road trip", "n1", "T", "B")).isFalse()
    }
}
