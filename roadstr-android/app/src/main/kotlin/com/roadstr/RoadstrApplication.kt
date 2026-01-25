package com.roadstr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.roadstr.nostr.NostrClient
import com.roadstr.osmand.MapLayerManager
import com.roadstr.storage.EventCache
import com.roadstr.storage.KeyStore
import com.roadstr.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow

class RoadstrApplication : Application() {

    lateinit var settings: Settings
        private set
    lateinit var keyStore: KeyStore
        private set
    lateinit var eventCache: EventCache
        private set

    // Shared NostrClient set by the service when running
    var nostrClient: NostrClient? = null

    // Shared MapLayerManager set by the service when running
    var mapLayerManager: MapLayerManager? = null

    // Observable OsmAnd connection state
    val osmandConnected = MutableStateFlow(false)

    // Observable relay connection count (updated by service)
    val relayConnectionCount = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()

        settings = Settings(this)
        keyStore = KeyStore(settings)
        eventCache = EventCache()

        detectOsmandPackage()
        createServiceNotificationChannel()
    }

    private fun detectOsmandPackage() {
        val candidates = listOf(
            "net.osmand.plus",
            "net.osmand",
            "net.osmand.dev"
        )
        for (pkg in candidates) {
            if (isPackageInstalled(pkg)) {
                settings.osmandPackage = pkg
                return
            }
        }
        // Keep default if none found
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun createServiceNotificationChannel() {
        val channel = NotificationChannel(
            "roadstr_service",
            "Roadstr Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background monitoring for road events"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
