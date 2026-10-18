package ai.agentshelf.artifactmcp.geo

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.bridge.LocationControl
import ai.agentshelf.artifactmcp.bridge.LocationDemand
import ai.agentshelf.artifactmcp.notify.Notifications
import ai.agentshelf.artifactmcp.ui.MainActivity
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.annotation.SuppressLint
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat

/**
 * Foreground service (type `location`) that runs only while some artifact has an active
 * `geo.watch` or `audio.geotrigger`. It is started from the visible viewer, so the while-in-use
 * location permission is enough and keeps working with the screen off.
 *
 * Provider choice: the framework [LocationManager] through `LocationManagerCompat`. On Android
 * 12+ it uses the platform `fused` provider (backed by Google Play services where present);
 * otherwise GPS or network. This avoids a hard dependency on Play services for an OSS sideload.
 */
class LocationService : Service() {
    companion object {
        private const val TAG = "LocationService"
        const val ACTION_UPDATE = "ai.agentshelf.artifactmcp.location.UPDATE"
        const val EXTRA_INTERVAL = "intervalMs"
        const val EXTRA_HIGH_ACCURACY = "highAccuracy"
        const val EXTRA_LABELS = "labels"
        private const val TIMEOUT_CHECK_MS = 15_000L

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Provider for a request: fused when the platform has it, else GPS or network. */
        @SuppressLint("InlinedApi") // FUSED_PROVIDER is returned only when sdk >= 31
        fun chooseProvider(sdk: Int, hasFused: Boolean, gpsEnabled: Boolean, networkEnabled: Boolean, highAccuracy: Boolean): String? = when {
            sdk >= Build.VERSION_CODES.S && hasFused -> LocationManager.FUSED_PROVIDER
            highAccuracy && gpsEnabled -> LocationManager.GPS_PROVIDER
            networkEnabled -> LocationManager.NETWORK_PROVIDER
            gpsEnabled -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: LocationManager
    private var current: LocationDemand? = null
    private var provider: String? = null

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            app.hub.onFix(Fix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time))
        }

        override fun onProviderDisabled(provider: String) {
            app.hub.onLocationUnavailable()
        }
    }

    private val timeoutCheck = object : Runnable {
        override fun run() {
            app.hub.checkTimeouts()
            handler.postDelayed(this, TIMEOUT_CHECK_MS)
        }
    }

    private val app get() = application as ArtifactApp

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val demand = intent?.takeIf { it.action == ACTION_UPDATE }?.let {
            LocationDemand(
                intervalMs = it.getLongExtra(EXTRA_INTERVAL, 15_000L).coerceAtLeast(15_000L),
                highAccuracy = it.getBooleanExtra(EXTRA_HIGH_ACCURACY, false),
                labels = it.getStringArrayListExtra(EXTRA_LABELS).orEmpty(),
            )
        }
        if (demand == null || !hasLocationPermission(this)) {
            // Restarted by the system without a request, or permission was withdrawn.
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.LOCATION_NOTIFICATION_ID,
                buildNotification(demand.labels),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "foreground start not allowed")
            } else if (e !is SecurityException) {
                throw e
            }
            app.hub.onLocationUnavailable()
            stopSelf()
            return START_NOT_STICKY
        }
        request(demand)
        // Not sticky: a restart from the background could not hold while-in-use location.
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission") // checked by hasLocationPermission() in onStartCommand
    private fun request(demand: LocationDemand) {
        if (demand == current) return
        val chosen = chooseProvider(
            sdk = Build.VERSION.SDK_INT,
            hasFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER),
            gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER),
            networkEnabled = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER),
            highAccuracy = demand.highAccuracy,
        )
        stopUpdates()
        current = demand
        provider = chosen
        if (chosen == null || !LocationManagerCompat.isLocationEnabled(manager)) {
            app.hub.onLocationUnavailable()
        }
        if (chosen != null && hasLocationPermission(this)) {
            val request = LocationRequestCompat.Builder(demand.intervalMs)
                .setQuality(
                    if (demand.highAccuracy) LocationRequestCompat.QUALITY_HIGH_ACCURACY
                    else LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY,
                )
                .setMinUpdateIntervalMillis(demand.intervalMs / 2)
                .build()
            LocationManagerCompat.requestLocationUpdates(manager, chosen, request, ContextCompat.getMainExecutor(this), listener)
        }
        handler.removeCallbacks(timeoutCheck)
        handler.postDelayed(timeoutCheck, TIMEOUT_CHECK_MS)
    }

    private fun buildNotification(labels: List<String>): android.app.Notification {
        val subject = when (labels.size) {
            0 -> getString(R.string.location_subject_unknown)
            1 -> labels[0]
            else -> resources.getQuantityString(R.plurals.location_subject_many, labels.size - 1, labels[0], labels.size - 1)
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, LocationStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_LOCATION)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle(getString(R.string.location_notification_title, getString(R.string.app_name), subject))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.location_stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission") // removing updates needs no permission; SecurityException handled
    private fun stopUpdates() {
        try {
            LocationManagerCompat.removeUpdates(manager, listener)
        } catch (_: SecurityException) {
            // Permission was withdrawn: there is nothing left to remove.
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutCheck)
        stopUpdates()
        super.onDestroy()
    }
}

/** "Stop" on the location notification: ends every watch and geotrigger. */
class LocationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as ArtifactApp).hub.stopAllLocation()
    }
}

/** Starts, updates and stops [LocationService] for the hub's current demand. */
class ServiceLocationControl(private val context: Context) : LocationControl {
    private var running = false

    override fun update(demand: LocationDemand?): Boolean {
        val intent = Intent(context, LocationService::class.java)
        if (demand == null) {
            if (running) context.stopService(intent)
            running = false
            return true
        }
        if (!LocationService.hasLocationPermission(context)) return false
        intent.action = LocationService.ACTION_UPDATE
        intent.putExtra(LocationService.EXTRA_INTERVAL, demand.intervalMs)
        intent.putExtra(LocationService.EXTRA_HIGH_ACCURACY, demand.highAccuracy)
        intent.putStringArrayListExtra(LocationService.EXTRA_LABELS, ArrayList(demand.labels))
        return try {
            ContextCompat.startForegroundService(context, intent)
            running = true
            true
        } catch (e: IllegalStateException) {
            // Background start not allowed (ForegroundServiceStartNotAllowedException is a subclass).
            Log.w("LocationControl", "start failed: ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
