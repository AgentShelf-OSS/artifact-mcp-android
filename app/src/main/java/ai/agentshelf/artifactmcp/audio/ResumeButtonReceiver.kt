package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.car.CarErrors
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Media button receiver for playback resumption: a "play" from a headset, Bluetooth or the car
 * while the app is not running starts [PlaybackService], which resumes the newest Continue item
 * (`MediaSession.Callback.onPlaybackResumption`).
 *
 * The service is started in the foreground only when resumption can play: an origin is set,
 * Continue has an item, the library is not known to be signed out, and a network is up.
 * Otherwise nothing is started, so a service started in the foreground never stays without
 * playback (Android requires it to call startForeground soon after it starts).
 */
@OptIn(UnstableApi::class)
class ResumeButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val app = context.applicationContext as? ArtifactApp ?: return false
        val origin = app.originStore.origin
        val problem = CarErrors.resumption(
            originSet = origin != null,
            hasRecent = app.recents.forOrigin(origin).isNotEmpty(),
            library = runCatching { app.library.known() }.getOrNull(),
            online = app.isOnline(),
            forPlayback = true,
        )
        if (problem != null) Log.i("ArtifactCar", "media button: not resuming ($problem)")
        return problem == null
    }
}
