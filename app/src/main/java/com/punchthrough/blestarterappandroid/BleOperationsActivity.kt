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
import com.punchthrough.blestarterappandroid.databinding.DialogSensorSettingsBinding
import com.punchthrough.blestarterappandroid.databinding.DialogSystemSettingsBinding
import com.punchthrough.blestarterappandroid.databinding.RowDashboardTemplateBinding
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private val TRACK_TAIL_SERVICE_UUID: UUID =
    UUID.fromString("78563412-7856-3412-5678-123412345678")
private val TRACK_TAIL_WRITE_CHARACTERISTIC_UUID: UUID =
    UUID.fromString("78563412-7856-3412-5678-123412345680")
private val TRACK_TAIL_NOTIFICATION_CHARACTERISTIC_UUID: UUID =
    UUID.fromString("78563412-7856-3412-5678-123412345679")
private val CCCD_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class BleOperationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleOperationsBinding
    private val sensorSettings = mutableMapOf<String, SensorSetting>()
    private val pendingWrites = ConcurrentLinkedQueue<PendingWrite>()
    private var publishIntervalSeconds = DEFAULT_INTERVAL_SECONDS
    private var lastPublishedConfigStr: String? = null
    private val isCloudMode by lazy {
        intent.getBooleanExtra(EXTRA_CLOUD_MODE, false)
    }
    private val device: BluetoothDevice by lazy {
        intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleOperationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!isCloudMode) {
            ConnectionManager.registerListener(connectionEventListener)
        }
        MqttManager.connect()
        MqttManager.onMessageReceived = { topic, payload ->
            runOnUiThread {
                if (topic.endsWith("/sensor_data")) {
                    appendRawNotification(payload)
                } else if (topic.endsWith("/config_data")) {
                    appendRawConfNotification(payload)
                }
            }
        }
        MqttManager.subscribe("tracktrail/+/config_data")
        if (isCloudMode) {
            MqttManager.subscribe("tracktrail/+/sensor_data")
        }
        showDeviceDetails()
        setupDashboardRows()
        setupActions()
        if (!isCloudMode) {
            subscribeToNotifications()
            sendTimeSyncCmd()
        }
        showMainTab(showToast = false)
    }

    override fun onDestroy() {
        MqttManager.onMessageReceived = null
        MqttManager.disconnect()
        if (!isCloudMode) {
            ConnectionManager.unregisterListener(connectionEventListener)
        }
        if (!isCloudMode && isFinishing) {
            ConnectionManager.teardownConnection(device)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun showDeviceDetails() {
        if (isCloudMode) {
            binding.connectedDeviceName.text = "Connected to: Cloud"
            binding.connectedDeviceAddress.text = "Cloud mode"
            return
        }

        val name = if (hasRequiredBluetoothPermissions()) {
            device.name ?: getString(R.string.app_name)
        } else {
            getString(R.string.app_name)
        }
        binding.connectedDeviceName.text = "Connected to: $name"
        binding.connectedDeviceAddress.text = "MAC: ${device.address}"
    }

    private fun setupActions() {
        binding.settingsButton.setOnClickListener { showSystemSettingsDialog() }
        binding.mainTab.setOnClickListener { showMainTab(showToast = true) }
        binding.rawDataTab.setOnClickListener { showRawDataTab() }
        binding.rawConfDataTab.setOnClickListener { showRawConfDataTab() }
        binding.clearButton.setOnClickListener { binding.rawDataText.text = "" }
        binding.pauseButton.setOnClickListener { toast("Pause pressed") }
        binding.saveLogButton.setOnClickListener { toast("Save Log pressed") }
        binding.confClearButton.setOnClickListener { binding.rawConfDataText.text = "" }
        binding.confPauseButton.setOnClickListener { toast("Conf Pause pressed") }
        binding.confSaveLogButton.setOnClickListener { toast("Conf Save Log pressed") }
    }

    private fun subscribeToNotifications() {
        val service = ConnectionManager.servicesOnDevice(device)
            ?.firstOrNull { it.uuid == TRACK_TAIL_SERVICE_UUID }
        if (service == null) {
            Timber.e("Notification service not found: $TRACK_TAIL_SERVICE_UUID")
            toast("Notification setup failed: BLE service not found")
            return
        }
        Timber.i("Notification service found: ${service.uuid}")

        val characteristic =
            service.getCharacteristic(TRACK_TAIL_NOTIFICATION_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Timber.e(
                "Notification characteristic not found: " +
                    TRACK_TAIL_NOTIFICATION_CHARACTERISTIC_UUID
            )
            toast("Notification setup failed: characteristic not found")
            return
        }
        Timber.i("Notification characteristic found: ${characteristic.uuid}")

        if (characteristic.getDescriptor(CCCD_UUID) == null) {
            Timber.e(
                "Notification CCCD not found: characteristic=${characteristic.uuid}, " +
                    "descriptor=$CCCD_UUID"
            )
            toast("Notification setup failed: CCCD not found")
            return
        }
        Timber.i("Notification CCCD found: $CCCD_UUID")

        ConnectionManager.enableNotifications(device, characteristic)
    }

    private fun appendRawNotification(payload: String) {
        val line = payload.trimEnd('\r', '\n')
        if (line.isEmpty()) return

        if (line.contains("\"config_str\"")) {
            handleConfigStrFromBle(line)
            return
        }

        updateSensorValues(line)
        if (!isCloudMode) {
            MqttManager.publish("tracktrail/AABBCCDDEEFF/sensor_data", line)
        }
        val currentText = binding.rawDataText.text
        binding.rawDataText.text = if (currentText.isNullOrEmpty()) {
            line
        } else {
            "$currentText\n$line"
        }
        binding.rawDataScrollView.post {
            binding.rawDataScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun handleConfigStrFromBle(payload: String) {
        applyConfigStrSettings(payload)
        MqttManager.publish("tracktrail/AABBCCDDEEFF/config_data", payload)
        lastPublishedConfigStr = payload
        appendToRawConfText(payload)
    }

    private fun applyConfigStrSettings(payload: String) {
        try {
            val json = JSONObject(payload)
            val settings = json.optJSONObject("settings") ?: return
            val sensors = settings.optJSONObject("sensors") ?: return
            for (key in sensors.keys()) {
                val config = sensors.getJSONObject(key)
                sensorSettings[key] = SensorSetting(
                    interval = config.optInt("interval", DEFAULT_INTERVAL_SECONDS),
                    enabled = config.optBoolean("enabled", true)
                )
            }
        } catch (e: JSONException) {
            Timber.w(e, "Failed to parse config_str settings")
        }
    }

    private fun appendRawConfNotification(payload: String) {
        val line = payload.trimEnd('\r', '\n')
        if (line.isEmpty()) return

        if (line.contains("\"config_str\"")) {
            applyConfigStrSettings(line)
            if (!isCloudMode && line == lastPublishedConfigStr) {
                lastPublishedConfigStr = null
                appendToRawConfText(line)
                return
            }
        }

        if (!isCloudMode) {
            sendBleCommand("Config forward", "GET_DATA_FROM_BLE:$line")
        }
        appendToRawConfText(line)
    }

    private fun appendToRawConfText(line: String) {
        val currentText = binding.rawConfDataText.text
        binding.rawConfDataText.text = if (currentText.isNullOrEmpty()) {
            line
        } else {
            "$currentText\n$line"
        }
        binding.rawConfDataScrollView.post {
            binding.rawConfDataScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateSensorValues(payload: String) {
        try {
            val json = JSONObject(payload)
            if (json.optString("type") != "sensor_data") return
            updateDummyValue(json)
            updateDummy2Value(json)
            updateGpsValue(json)
        } catch (exception: JSONException) {
            Timber.w(exception, "Notification payload is not valid JSON")
        }
    }

    private fun updateDummyValue(json: JSONObject) {
        val dummyData = json.optJSONObject("sensor_dummy_data") ?: return
        if (dummyData.optString("name") == "sensor_dummy" && dummyData.has("val")) {
            val value = dummyData.get("val").toString()
            binding.updatedRow.metricValue.text = value
            Timber.i("Dummy sensor value updated: $value")
        }
    }

    private fun updateDummy2Value(json: JSONObject) {
        val dummyData = json.optJSONObject("sensor_dummy_2_data") ?: return
        if (dummyData.optString("name") == "sensor_dummy_2" && dummyData.has("val")) {
            val value = dummyData.get("val").toString()
            binding.wifiRow.metricValue.text = value
            Timber.i("Dummy_2 sensor value updated: $value")
        }
    }

    private fun updateGpsValue(json: JSONObject) {
        val gps = json.optJSONObject("gps") ?: return
        val latitude = gps.optString("lat")
        val longitude = gps.optString("lon")
        if (latitude.isEmpty() || longitude.isEmpty()) return

        binding.gpsRow.metricValue.text = "$latitude\n$longitude"
        Timber.i("GPS location updated: lat=$latitude, lon=$longitude")
    }

    private fun setupDashboardRows() {
        configureRow(
            binding.heartRateRow,
            "♡",
            "Heart Rate",
            "?\nBPM",
            "sensor_hr"
        ) {
            sendHeartRateRefresh()
        }
        configureRow(
            binding.stepsRow,
            "♧",
            "Step Count",
            "?\nsteps",
            "sensor_steps"
        )
        configureRow(
            binding.temperatureRow,
            "♨",
            "Temperature",
            "?\n°C",
            "sensor_temperature"
        )
        configureRow(
            binding.batteryRow,
            "▯",
            "Battery Level",
            "?\n%",
            "sensor_battery"
        )
        configureRow(
            binding.accelerometerRow,
            "⌁",
            "Accelerometer",
            "?\n?, ?",
            "sensor_accelerometer"
        )
        configureRow(
            binding.gpsRow,
            "⌖",
            "GPS Location",
            "?\n?",
            "sensor_gps"
        ) {
            sendSensorRefresh("GPS Location", "sensor_gps")
        }
        configureRow(
            binding.updatedRow,
            "◷",
            "Dummy",
            "--",
            "sensor_dummy"
        ) {
            sendSensorRefresh("Dummy", "sensor_dummy")
        }
        configureRow(
            binding.wifiRow,
            "≋",
            "Dummy_2",
            "?\ndBm",
            "sensor_dummy_2"
        ) {
            sendSensorRefresh("Dummy_2", "sensor_dummy_2")
        }
    }

    private fun configureRow(
        row: RowDashboardTemplateBinding,
        icon: String,
        label: String,
        value: String,
        sensorName: String,
        onRefresh: (() -> Unit)? = null
    ) {
        row.metricIcon.text = icon
        row.metricName.text = label
        row.metricValue.text = value
        row.refreshButton.setOnClickListener {
            onRefresh?.invoke() ?: toast("$label refresh pressed")
        }
        row.metricSettingsButton.setOnClickListener {
            showSensorSettingsDialog(icon, label, sensorName)
        }
    }

    private fun sendHeartRateRefresh() {
        Timber.i("Refresh button clicked: Heart Rate")

        sendSensorRefresh("Heart Rate", "sensor_hr")
    }

    private fun sendTimeSyncCmd() {
        val ts = System.currentTimeMillis()
        val unixSeconds = ts / 1000
        val command = "GET_DATA_FROM_BLE:{\"ID\":\"1\",\"time\":$ts,\"action\":{\"msg_id\":5,\"time\":$unixSeconds}}"
        sendBleCommand("Time sync", command)
    }

    private fun sendSensorRefresh(label: String, sensorName: String) {
        Timber.i("Refresh button clicked: $label")
        sendBleCommand("$label request", buildSensorRefreshCommand(sensorName))
    }

    private fun sendBleCommand(label: String, command: String): Boolean {
        Timber.i("JSON payload generated: $command")
        if (isCloudMode) {
            val jsonPayload = command.removePrefix("GET_DATA_FROM_BLE:")
            MqttManager.publish("tracktrail/AABBCCDDEEFF/config_data", jsonPayload)
            return true
        }

        val service = ConnectionManager.servicesOnDevice(device)
            ?.firstOrNull { it.uuid == TRACK_TAIL_SERVICE_UUID }
        if (service == null) {
            Timber.e("Service not found: $TRACK_TAIL_SERVICE_UUID")
            toast("$label failed: BLE service not found")
            return false
        }
        Timber.i("Service found: ${service.uuid}")

        val characteristic = service.getCharacteristic(TRACK_TAIL_WRITE_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Timber.e(
                "Characteristic not found: service=$TRACK_TAIL_SERVICE_UUID, " +
                    "characteristic=$TRACK_TAIL_WRITE_CHARACTERISTIC_UUID"
            )
            toast("$label failed: command characteristic not found")
            return false
        }
        Timber.i("Characteristic found: ${characteristic.uuid}")

        if (!isWriteWithResponseSupported(characteristic)) {
            Timber.e(
                "Characteristic ${characteristic.uuid} does not support WRITE_TYPE_DEFAULT"
            )
            toast("$label failed: characteristic is not writable")
            return false
        }

        Timber.i("Submitting BLE write to existing ConnectionManager queue")
        val pendingWrite = PendingWrite(label)
        pendingWrites.add(pendingWrite)
        val queued = ConnectionManager.writeCharacteristic(
            device,
            characteristic,
            command.toByteArray(Charsets.UTF_8)
        )
        if (!queued) {
            pendingWrites.remove(pendingWrite)
            toast("$label failed: BLE is not connected")
            return false
        }
        if (pendingWrites.contains(pendingWrite)) {
            toast("$label queued")
        }
        return true
    }

    private fun buildSensorRefreshCommand(sensorName: String): String {
        return "GET_DATA_FROM_BLE:{" +
            " \"ID\": \"1\"," +
            " \"time\": ${System.currentTimeMillis()}," +
            " \"action\": { \"msg_id\": 2, \"sensor\": \"$sensorName\" }" +
            " }"
    }

    private fun buildSensorEnableCommand(sensorName: String, msgId: Int): String {
        return "GET_DATA_FROM_BLE:{" +
            " \"ID\": \"1\"," +
            " \"time\": ${System.currentTimeMillis()}," +
            " \"action\": {" +
            " \"msg_id\": $msgId," +
            " \"sensor\": \"$sensorName\"" +
            " }" +
            " }"
    }

    private fun buildSensorSettingsCommand(sensorName: String, interval: Int): String {
        return "GET_DATA_FROM_BLE:{" +
            " \"ID\": \"1\"," +
            " \"time\": ${System.currentTimeMillis()}," +
            " \"action\": {" +
            " \"msg_id\": 1," +
            " \"sensor\": \"$sensorName\"," +
            " \"interval\": $interval" +
            " }" +
            " }"
    }

    private fun buildSystemSettingsCommand(interval: Int): String {
        return "GET_DATA_FROM_BLE:{" +
            " \"ID\": \"1\"," +
            " \"time\": ${System.currentTimeMillis()}," +
            " \"action\": {" +
            " \"msg_id\": 6," +
            " \"publish_interval\": $interval" +
            " }" +
            " }"
    }

    @SuppressLint("SetTextI18n")
    private fun showSystemSettingsDialog() {
        var selectedInterval = publishIntervalSeconds
        val dialogBinding = DialogSystemSettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.intervalValue.text = selectedInterval.toString()
        dialogBinding.decreaseButton.setOnClickListener {
            selectedInterval = (selectedInterval - INTERVAL_STEP_SECONDS)
                .coerceAtLeast(MIN_INTERVAL_SECONDS)
            dialogBinding.intervalValue.text = selectedInterval.toString()
        }
        dialogBinding.increaseButton.setOnClickListener {
            selectedInterval = (selectedInterval + INTERVAL_STEP_SECONDS)
                .coerceAtMost(MAX_INTERVAL_SECONDS)
            dialogBinding.intervalValue.text = selectedInterval.toString()
        }
        dialogBinding.closeButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.okButton.setOnClickListener {
            val command = buildSystemSettingsCommand(selectedInterval)
            if (sendBleCommand("System settings", command)) {
                publishIntervalSeconds = selectedInterval
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * DIALOG_WIDTH_RATIO).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun showSensorSettingsDialog(
        icon: String,
        label: String,
        sensorName: String
    ) {
        val currentSetting = sensorSettings.getOrPut(sensorName) { SensorSetting() }
        var selectedInterval = currentSetting.interval
        val dialogBinding = DialogSensorSettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.sensorIcon.text = icon
        dialogBinding.dialogTitle.text = "$label Settings"
        dialogBinding.enabledDescription.text = "Enable or disable $label sensor updates."
        dialogBinding.enabledSwitch.isChecked = currentSetting.enabled
        dialogBinding.intervalValue.text = selectedInterval.toString()

        dialogBinding.decreaseButton.setOnClickListener {
            selectedInterval = (selectedInterval - INTERVAL_STEP_SECONDS)
                .coerceAtLeast(MIN_INTERVAL_SECONDS)
            dialogBinding.intervalValue.text = selectedInterval.toString()
        }
        dialogBinding.increaseButton.setOnClickListener {
            selectedInterval = (selectedInterval + INTERVAL_STEP_SECONDS)
                .coerceAtMost(MAX_INTERVAL_SECONDS)
            dialogBinding.intervalValue.text = selectedInterval.toString()
        }
        dialogBinding.closeButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.okButton.setOnClickListener {
            val intervalChanged = selectedInterval != currentSetting.interval
            val enabledChanged = dialogBinding.enabledSwitch.isChecked != currentSetting.enabled

            if (!intervalChanged && !enabledChanged) {
                dialog.dismiss()
                return@setOnClickListener
            }

            if (enabledChanged) {
                val msgId = if (dialogBinding.enabledSwitch.isChecked) 3 else 4
                val command = buildSensorEnableCommand(sensorName, msgId)
                sendBleCommand("$label ${if (msgId == 3) "enable" else "disable"}", command)
            }

            if (intervalChanged) {
                val command = buildSensorSettingsCommand(sensorName, selectedInterval)
                sendBleCommand("$label settings", command)
            }

            currentSetting.interval = selectedInterval
            currentSetting.enabled = dialogBinding.enabledSwitch.isChecked
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * DIALOG_WIDTH_RATIO).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
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
        binding.rawConfDataContent.visibility = View.GONE
        selectTab(binding.mainTab, binding.rawDataTab, binding.rawConfDataTab)
    }

    private fun showRawDataTab() {
        binding.mainContent.visibility = View.GONE
        binding.rawDataContent.visibility = View.VISIBLE
        binding.rawConfDataContent.visibility = View.GONE
        selectTab(binding.rawDataTab, binding.mainTab, binding.rawConfDataTab)
    }

    private fun showRawConfDataTab() {
        binding.mainContent.visibility = View.GONE
        binding.rawDataContent.visibility = View.GONE
        binding.rawConfDataContent.visibility = View.VISIBLE
        selectTab(binding.rawConfDataTab, binding.mainTab, binding.rawDataTab)
    }

    private fun selectTab(selected: TextView, vararg others: TextView) {
        selected.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
        selected.setBackgroundResource(R.drawable.bg_tab_selected)
        for (tab in others) {
            tab.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
            tab.setBackgroundColor(Color.TRANSPARENT)
        }
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
                    val label = pendingWrites.poll()?.label ?: "BLE command"
                    Timber.i(
                        "BLE write success callback received for $label: ${characteristic.uuid}"
                    )
                    runOnUiThread {
                        toast("$label sent successfully")
                    }
                }
            }
            onCharacteristicWriteFailed = { callbackDevice, characteristic, status ->
                if (callbackDevice == device &&
                    characteristic.uuid == TRACK_TAIL_WRITE_CHARACTERISTIC_UUID
                ) {
                    val label = pendingWrites.poll()?.label ?: "BLE command"
                    Timber.e(
                        "BLE write failure callback received for $label: " +
                            "characteristic=${characteristic.uuid}, status=$status"
                    )
                    runOnUiThread {
                        toast("$label failed (BLE status $status)")
                    }
                }
            }
            onNotificationsEnabled = { callbackDevice, characteristic ->
                if (callbackDevice == device &&
                    characteristic.uuid == TRACK_TAIL_NOTIFICATION_CHARACTERISTIC_UUID
                ) {
                    Timber.i(
                        "Notification subscription success: ${characteristic.uuid}"
                    )
                    runOnUiThread {
                        toast("BLE notifications enabled")
                    }
                }
            }
            onCharacteristicChanged = { callbackDevice, characteristic, value ->
                if (callbackDevice == device &&
                    characteristic.uuid == TRACK_TAIL_NOTIFICATION_CHARACTERISTIC_UUID
                ) {
                    val payload = value.toString(Charsets.UTF_8)
                    Timber.i("Notification received: ${characteristic.uuid}")
                    Timber.i("Raw payload string: $payload")
                    runOnUiThread {
                        appendRawNotification(payload)
                    }
                }
            }
        }
    }

    private data class SensorSetting(
        var interval: Int = DEFAULT_INTERVAL_SECONDS,
        var enabled: Boolean = true
    )

    private data class PendingWrite(val label: String)

    companion object {
        const val EXTRA_CLOUD_MODE = "extra_cloud_mode"
        const val DEFAULT_INTERVAL_SECONDS = 30
        const val INTERVAL_STEP_SECONDS = 5
        const val MIN_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 3600
        const val DIALOG_WIDTH_RATIO = 0.90
    }
}
