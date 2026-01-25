package com.roadstr.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.roadstr.R
import com.roadstr.RoadstrApplication
import com.roadstr.RoadstrService
import com.roadstr.databinding.ActivityMainBinding
import com.roadstr.model.ConfirmationStatus
import com.roadstr.model.EventType
import com.roadstr.model.RoadEvent
import com.roadstr.nostr.EventSigner
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: RoadstrApplication
    private var serviceRunning = false
    private lateinit var eventAdapter: EventAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as RoadstrApplication

        // Initialize adapter with callbacks
        eventAdapter = EventAdapter(
            onConfirm = { event -> publishConfirmation(event, ConfirmationStatus.STILL_THERE) },
            onDeny = { event -> publishConfirmation(event, ConfirmationStatus.NO_LONGER_THERE) },
            onLocationClick = { event -> openLocationInOsmAnd(event) }
        )

        setupViews()
        requestPermissions()
        observeConnectionStatus()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupViews() {
        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        // RecyclerView setup
        binding.recyclerEvents.layoutManager = LinearLayoutManager(this)
        binding.recyclerEvents.adapter = eventAdapter

        // Start/Stop card
        binding.cardStartStop.setOnClickListener {
            if (serviceRunning) {
                stopService()
            } else {
                startService()
            }
        }

        // Report card
        binding.cardReport.setOnClickListener {
            startActivity(Intent(this, ReportDialogActivity::class.java))
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SettingsFragment())
                .addToBackStack(null)
                .commit()
            binding.mainContent.visibility = View.GONE
            binding.fragmentContainer.visibility = View.VISIBLE
        }

        // Keys button
        binding.btnKeyManagement.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, KeyManagementFragment())
                .addToBackStack(null)
                .commit()
            binding.mainContent.visibility = View.GONE
            binding.fragmentContainer.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            binding.mainContent.visibility = View.VISIBLE
            binding.fragmentContainer.visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }

    private fun updateUI() {
        val eventCount = app.eventCache.getReportCount()
        
        // Update status text
        val statusText = if (serviceRunning) "Active" else "Stopped"
        binding.txtStatus.text = statusText
        binding.txtStatusMini.text = statusText
        binding.textStartStop.text = if (serviceRunning) "Stop" else "Start"
        
        // Update status indicators
        val statusColor = if (serviceRunning) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        binding.statusIndicatorMini.isActivated = serviceRunning
        
        // Update start/stop icon
        binding.iconStartStop.setImageResource(if (serviceRunning) R.drawable.ic_stop else R.drawable.ic_play)
        
        // Update event count badge
        if (eventCount > 0 && serviceRunning) {
            binding.txtEventCount.visibility = View.VISIBLE
            binding.txtEventCount.text = eventCount.toString()
            binding.iconNoEvents.visibility = View.GONE
        } else {
            binding.txtEventCount.visibility = View.GONE
            binding.iconNoEvents.visibility = if (serviceRunning) View.VISIBLE else View.GONE
        }

        // Update pubkey display
        val pubkey = app.keyStore.getNpub()
        binding.txtPubkey.text = pubkey?.take(12)?.plus("...") ?: "No key configured"

        // Update event list
        val events = app.eventCache.getAllActiveReports()
        eventAdapter.setEvents(events)
        
        // Show/hide empty state
        if (events.isEmpty() && serviceRunning) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerEvents.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerEvents.visibility = View.VISIBLE
        }

        updateConnectionStatus()
    }

    private fun startService() {
        if (!app.keyStore.hasKey()) {
            app.keyStore.generateKeyPair()
        }
        val intent = Intent(this, RoadstrService::class.java).apply {
            action = RoadstrService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        updateUI()
    }

    private fun stopService() {
        val intent = Intent(this, RoadstrService::class.java).apply {
            action = RoadstrService.ACTION_STOP
        }
        stopService(intent)
        serviceRunning = false
        updateUI()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            // Permissions already granted, auto-start
            autoStart()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                autoStart()
            }
        }
    }

    private fun autoStart() {
        if (!serviceRunning) {
            startService()
        }
    }

    private fun observeConnectionStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.relayConnectionCount.collect {
                        updateConnectionStatus()
                    }
                }
                launch {
                    app.osmandConnected.collect {
                        updateConnectionStatus()
                    }
                }
                launch {
                    app.eventCache.eventsChanged.collect {
                        updateEventList()
                    }
                }
            }
        }
    }

    private fun updateEventList() {
        val events = app.eventCache.getAllActiveReports()
        runOnUiThread {
            eventAdapter.setEvents(events)
            val eventCount = events.size
            if (serviceRunning && eventCount > 0) {
                binding.txtEventCount.visibility = View.VISIBLE
                binding.txtEventCount.text = eventCount.toString()
                binding.iconNoEvents.visibility = View.GONE
            } else {
                binding.txtEventCount.visibility = View.GONE
                binding.iconNoEvents.visibility = if (serviceRunning) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateConnectionStatus() {
        val relayConnected = app.relayConnectionCount.value
        val relayTotal = app.settings.relays.size
        val osmandUp = app.osmandConnected.value

        val text = "$relayConnected/$relayTotal relays"
        
        val color = when {
            relayConnected > 0 && osmandUp -> Color.parseColor("#10B981") // success - green
            relayConnected > 0 -> Color.parseColor("#F59E0B") // warning - relays ok but no OsmAnd
            else -> Color.parseColor("#EF4444") // error - red
        }

        runOnUiThread {
            binding.txtConnectionStatus.text = text
            binding.txtConnectionStatus.setTextColor(color)
        }
    }

    private fun publishConfirmation(event: RoadEvent, status: ConfirmationStatus) {
        val privKey = app.keyStore.getPrivateKey()
        if (privKey == null) {
            Toast.makeText(this, "No key configured", Toast.LENGTH_SHORT).show()
            return
        }

        val nostrClient = app.nostrClient
        if (nostrClient == null) {
            Toast.makeText(this, "Service not running", Toast.LENGTH_SHORT).show()
            return
        }

        val confirmEvent = EventSigner.createConfirmationEvent(event.id, status, event.lat, event.lon, privKey)
        nostrClient.publish(confirmEvent)

        val message = if (status == ConfirmationStatus.STILL_THERE) "Confirmed" else "Denied"
        Toast.makeText(this, "$message: ${event.type.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun openLocationInOsmAnd(event: RoadEvent) {
        val geoUri = Uri.parse("geo:${event.lat},${event.lon}?z=17")
        val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            setPackage(app.settings.osmandPackage)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to any app that handles geo:
            try {
                startActivity(Intent(Intent.ACTION_VIEW, geoUri))
            } catch (e2: Exception) {
                Toast.makeText(this, "No map app available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}

class EventAdapter(
    private val onConfirm: (RoadEvent) -> Unit,
    private val onDeny: (RoadEvent) -> Unit,
    private val onLocationClick: (RoadEvent) -> Unit
) : RecyclerView.Adapter<EventAdapter.ViewHolder>() {

    private var events: List<RoadEvent> = emptyList()

    fun setEvents(newEvents: List<RoadEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtType: TextView = view.findViewById(R.id.txt_event_type)
        val txtDetails: TextView = view.findViewById(R.id.txt_event_details)
        val txtTime: TextView = view.findViewById(R.id.txt_time)
        val txtConfirmations: TextView = view.findViewById(R.id.txt_confirmations)
        val txtDenials: TextView = view.findViewById(R.id.txt_denials)
        val colorIndicator: View = view.findViewById(R.id.color_indicator)
        val txtIcon: TextView = view.findViewById(R.id.txt_icon)
        val cardConfirmations: MaterialCardView = view.findViewById(R.id.card_confirmations)
        val cardDenials: MaterialCardView = view.findViewById(R.id.card_denials)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]
        holder.txtType.text = event.type.displayName
        holder.txtDetails.text = "%.5f, %.5f".format(event.lat, event.lon)
        holder.txtTime.text = formatTimeAgo(event.createdAt)
        holder.txtConfirmations.text = event.confirmCount.toString()
        holder.txtDenials.text = event.denyCount.toString()
        holder.colorIndicator.setBackgroundColor(Color.parseColor(event.type.colorHex))
        holder.txtIcon.text = getEmojiForType(event.type)

        // Make coordinates clickable to open in OsmAnd
        holder.txtDetails.setOnClickListener {
            onLocationClick(event)
        }

        // Confirm/Deny via clickable count badges
        holder.cardConfirmations.setOnClickListener {
            onConfirm(event)
        }
        holder.cardDenials.setOnClickListener {
            onDeny(event)
        }
    }

    override fun getItemCount() = events.size

    private fun getEmojiForType(type: EventType): String = when (type) {
        EventType.POLICE -> "👮"
        EventType.SPEED_CAMERA -> "📷"
        EventType.TRAFFIC_JAM -> "🚗"
        EventType.ACCIDENT -> "💥"
        EventType.ROAD_CLOSURE -> "🚫"
        EventType.CONSTRUCTION -> "🚧"
        EventType.HAZARD -> "⚠️"
        EventType.ROAD_CONDITION -> "🛣️"
        EventType.POTHOLE -> "🕳️"
        EventType.FOG -> "🌫️"
        EventType.ICE -> "🧊"
        EventType.ANIMAL -> "🦌"
        EventType.OTHER -> "ℹ️"
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis() / 1000
        val diff = now - timestamp
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ago"
            else -> "${diff / 86400}d ago"
        }
    }
}
