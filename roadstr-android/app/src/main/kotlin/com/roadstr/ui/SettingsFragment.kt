package com.roadstr.ui

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import com.roadstr.RoadstrApplication
import com.roadstr.model.EventType

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var app: RoadstrApplication

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        app = requireActivity().application as RoadstrApplication

        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        // Relay management
        val relayCategory = PreferenceCategory(context).apply {
            title = "Relays"
        }
        screen.addPreference(relayCategory)

        val relayListPref = Preference(context).apply {
            title = "Manage Relays"
            summary = "${app.settings.relays.size} relay(s) configured"
            setOnPreferenceClickListener {
                showRelayDialog()
                true
            }
        }
        relayCategory.addPreference(relayListPref)

        // Alert preferences
        val alertCategory = PreferenceCategory(context).apply {
            title = "Alerts"
        }
        screen.addPreference(alertCategory)

        val alertEnabled = SwitchPreferenceCompat(context).apply {
            title = "Enable Alerts"
            isChecked = app.settings.alertEnabled
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.alertEnabled = newValue as Boolean
                true
            }
        }
        alertCategory.addPreference(alertEnabled)

        val alertDistance = SeekBarPreference(context).apply {
            title = "Alert Distance"
            min = 100
            max = 2000
            value = app.settings.alertDistance
            summary = "${app.settings.alertDistance}m"
            setOnPreferenceChangeListener { pref, newValue ->
                val dist = newValue as Int
                app.settings.alertDistance = dist
                pref.summary = "${dist}m"
                true
            }
        }
        alertCategory.addPreference(alertDistance)

        val alertSound = SwitchPreferenceCompat(context).apply {
            title = "Alert Sound"
            isChecked = app.settings.alertSound
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.alertSound = newValue as Boolean
                true
            }
        }
        alertCategory.addPreference(alertSound)

        val alertVibration = SwitchPreferenceCompat(context).apply {
            title = "Alert Vibration"
            isChecked = app.settings.alertVibration
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.alertVibration = newValue as Boolean
                true
            }
        }
        alertCategory.addPreference(alertVibration)

        // Speed threshold
        val speedCategory = PreferenceCategory(context).apply {
            title = "Query Settings"
        }
        screen.addPreference(speedCategory)

        val speedThreshold = SeekBarPreference(context).apply {
            title = "Speed Threshold (km/h)"
            summary = "Switch to wider geohash above this speed"
            min = 40
            max = 150
            value = app.settings.querySpeedThreshold
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.querySpeedThreshold = newValue as Int
                true
            }
        }
        speedCategory.addPreference(speedThreshold)

        // Event type visibility
        val typesCategory = PreferenceCategory(context).apply {
            title = "Visible Event Types"
        }
        screen.addPreference(typesCategory)

        for (type in EventType.entries) {
            val typePref = SwitchPreferenceCompat(context).apply {
                title = type.displayName
                isChecked = app.settings.visibleTypes.contains(type.value)
                setOnPreferenceChangeListener { _, newValue ->
                    val types = app.settings.visibleTypes.toMutableSet()
                    if (newValue as Boolean) {
                        types.add(type.value)
                    } else {
                        types.remove(type.value)
                    }
                    app.settings.visibleTypes = types
                    true
                }
            }
            typesCategory.addPreference(typePref)
        }

        // OsmAnd package
        val osmandCategory = PreferenceCategory(context).apply {
            title = "OsmAnd"
        }
        screen.addPreference(osmandCategory)

        val osmandPkg = ListPreference(context).apply {
            key = "osmand_package"
            title = "OsmAnd Package"
            entries = arrayOf("OsmAnd+", "OsmAnd Free", "OsmAnd Nightly")
            entryValues = arrayOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
            value = app.settings.osmandPackage
            setOnPreferenceChangeListener { _, newValue ->
                app.settings.osmandPackage = newValue as String
                true
            }
        }
        osmandCategory.addPreference(osmandPkg)

        preferenceScreen = screen
    }

    private fun showRelayDialog() {
        val relays = app.settings.relays.toMutableList()
        val items = relays.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Relays")
            .setItems(items) { _, which ->
                relays.removeAt(which)
                app.settings.relays = relays
            }
            .setPositiveButton("Add") { _, _ ->
                showAddRelayDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddRelayDialog() {
        val input = EditText(requireContext()).apply {
            hint = "wss://relay.example.com"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Add Relay")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("wss://") && url.length > 8 && !url.contains(" ")) {
                    val relays = app.settings.relays.toMutableList()
                    relays.add(url)
                    app.settings.relays = relays
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
