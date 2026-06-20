package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.ConnectionManager.parcelableExtraCompat
import com.punchthrough.blestarterappandroid.databinding.ActivityBleOperationsBinding

class BleOperationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleOperationsBinding
    private val device: BluetoothDevice by lazy {
        intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleOperationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionManager.registerListener(connectionEventListener)
        showDeviceDetails()
        setupDashboardRows()
        setupActions()
        showMainTab(showToast = false)
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        if (isFinishing) {
            ConnectionManager.teardownConnection(device)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun showDeviceDetails() {
        val name = if (hasRequiredBluetoothPermissions()) {
            device.name ?: getString(R.string.app_name)
        } else {
            getString(R.string.app_name)
        }
        binding.connectedDeviceName.text = "Connected to: $name"
        binding.connectedDeviceAddress.text = "MAC: ${device.address}"
    }

    private fun setupActions() {
        binding.settingsButton.setOnClickListener { toast("Settings pressed") }
        binding.mainTab.setOnClickListener { showMainTab(showToast = true) }
        binding.rawDataTab.setOnClickListener { showRawDataTab() }
        binding.clearButton.setOnClickListener { toast("Clear pressed") }
        binding.pauseButton.setOnClickListener { toast("Pause pressed") }
        binding.saveLogButton.setOnClickListener { toast("Save Log pressed") }
    }

    private fun setupDashboardRows() {
        configureRow(R.id.heart_rate_row, "♡", "Heart Rate", "72\nBPM")
        configureRow(R.id.steps_row, "♧", "Step Count", "1250\nsteps")
        configureRow(R.id.temperature_row, "♨", "Temperature", "37.1\n°C")
        configureRow(R.id.battery_row, "▯", "Battery Level", "82\n%")
        configureRow(R.id.accelerometer_row, "⌁", "Accelerometer", "0.12\n-0.03, 9.81")
        configureRow(R.id.gps_row, "⌖", "GPS Location", "12.9716\n77.5946")
        configureRow(R.id.wifi_row, "≋", "WiFi RSSI", "-56\ndBm")
        configureRow(R.id.updated_row, "◷", "Last Updated", "09:31:25\nUTC")
    }

    private fun configureRow(rowId: Int, icon: String, label: String, value: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.metric_icon).text = icon
        row.findViewById<TextView>(R.id.metric_name).text = label
        row.findViewById<TextView>(R.id.metric_value).text = value
        row.findViewById<View>(R.id.refresh_button).setOnClickListener {
            toast("$label refresh pressed")
        }
        row.findViewById<View>(R.id.metric_settings_button).setOnClickListener {
            toast("$label settings pressed")
        }
    }

    private fun showMainTab(showToast: Boolean) {
        binding.mainContent.visibility = View.VISIBLE
        binding.rawDataContent.visibility = View.GONE
        selectTab(binding.mainTab, binding.rawDataTab)
        if (showToast) toast("Main tab pressed")
    }

    private fun showRawDataTab() {
        binding.mainContent.visibility = View.GONE
        binding.rawDataContent.visibility = View.VISIBLE
        selectTab(binding.rawDataTab, binding.mainTab)
        toast("Raw Data tab pressed")
    }

    private fun selectTab(selected: TextView, unselected: TextView) {
        selected.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
        selected.setBackgroundResource(R.drawable.bg_tab_selected)
        unselected.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
        unselected.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    if (!isFinishing) {
                        AlertDialog.Builder(this@BleOperationsActivity)
                            .setTitle(R.string.disconnected)
                            .setMessage("Disconnected from device.")
                            .setPositiveButton(R.string.ok) { _, _ -> finish() }
                            .show()
                    }
                }
            }
        }
    }
}
