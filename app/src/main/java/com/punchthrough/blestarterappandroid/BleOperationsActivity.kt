package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
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
import com.punchthrough.blestarterappandroid.databinding.RowDashboardTemplateBinding
import timber.log.Timber
import java.util.UUID

private val TRACK_TAIL_SERVICE_UUID: UUID =
    UUID.fromString("78563412-7856-3412-5678-123412345678")
private val TRACK_TAIL_WRITE_CHARACTERISTIC_UUID: UUID =
    UUID.fromString("78563412-7856-3412-5678-123412345680")

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
        configureRow(binding.heartRateRow, "♡", "Heart Rate", "72\nBPM") {
            sendHeartRateRefresh()
        }
        configureRow(binding.stepsRow, "♧", "Step Count", "1250\nsteps")
        configureRow(binding.temperatureRow, "♨", "Temperature", "37.1\n°C")
        configureRow(binding.batteryRow, "▯", "Battery Level", "82\n%")
        configureRow(
            binding.accelerometerRow,
            "⌁",
            "Accelerometer",
            "0.12\n-0.03, 9.81"
        )
        configureRow(binding.gpsRow, "⌖", "GPS Location", "12.9716\n77.5946")
        configureRow(binding.wifiRow, "≋", "WiFi RSSI", "-56\ndBm")
        configureRow(binding.updatedRow, "◷", "Last Updated", "09:31:25\nUTC")
    }

    private fun configureRow(
        row: RowDashboardTemplateBinding,
        icon: String,
        label: String,
        value: String,
        onRefresh: (() -> Unit)? = null
    ) {
        row.metricIcon.text = icon
        row.metricName.text = label
        row.metricValue.text = value
        row.refreshButton.setOnClickListener {
            onRefresh?.invoke() ?: toast("$label refresh pressed")
        }
        row.metricSettingsButton.setOnClickListener {
            toast("$label settings pressed")
        }
    }

    private fun sendHeartRateRefresh() {
        Timber.i("Refresh button clicked: Heart Rate")

        val json = buildHeartRateRefreshJson()
        Timber.i("JSON payload generated: $json")

        val service = ConnectionManager.servicesOnDevice(device)
            ?.firstOrNull { it.uuid == TRACK_TAIL_SERVICE_UUID }
        if (service == null) {
            Timber.e("Service not found: $TRACK_TAIL_SERVICE_UUID")
            toast("Heart Rate request failed: BLE service not found")
            return
        }
        Timber.i("Service found: ${service.uuid}")

        val characteristic = service.getCharacteristic(TRACK_TAIL_WRITE_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Timber.e(
                "Characteristic not found: service=$TRACK_TAIL_SERVICE_UUID, " +
                    "characteristic=$TRACK_TAIL_WRITE_CHARACTERISTIC_UUID"
            )
            toast("Heart Rate request failed: writable characteristic not found")
            return
        }
        Timber.i("Characteristic found: ${characteristic.uuid}")

        if (!isWriteWithResponseSupported(characteristic)) {
            Timber.e(
                "Characteristic ${characteristic.uuid} does not support WRITE_TYPE_DEFAULT"
            )
            toast("Heart Rate request failed: characteristic is not writable")
            return
        }

        Timber.i("Submitting BLE write to existing ConnectionManager queue")
        val queued = ConnectionManager.writeCharacteristic(
            device,
            characteristic,
            json.toByteArray(Charsets.UTF_8)
        )
        toast(
            if (queued) {
                "Heart Rate request queued"
            } else {
                "Heart Rate request failed: BLE is not connected"
            }
        )
    }

    private fun buildHeartRateRefreshJson(): String {
        return "GET_DATA_FROM_BLE:{" +
            " \"ID\": \"1\"," +
            " \"time\": ${System.currentTimeMillis()}," +
            " \"action\": { \"msg_id\": 2, \"sensor\": \"sensor_hr\" }" +
            " }"
    }

    private fun isWriteWithResponseSupported(
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
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
            onCharacteristicWrite = { callbackDevice, characteristic ->
                if (callbackDevice == device &&
                    characteristic.uuid == TRACK_TAIL_WRITE_CHARACTERISTIC_UUID
                ) {
                    Timber.i(
                        "BLE write success callback received for Heart Rate refresh: " +
                            characteristic.uuid
                    )
                    runOnUiThread {
                        toast("Heart Rate request sent successfully")
                    }
                }
            }
            onCharacteristicWriteFailed = { callbackDevice, characteristic, status ->
                if (callbackDevice == device &&
                    characteristic.uuid == TRACK_TAIL_WRITE_CHARACTERISTIC_UUID
                ) {
                    Timber.e(
                        "BLE write failure callback received for Heart Rate refresh: " +
                            "characteristic=${characteristic.uuid}, status=$status"
                    )
                    runOnUiThread {
                        toast("Heart Rate request failed (BLE status $status)")
                    }
                }
            }
        }
    }
}
