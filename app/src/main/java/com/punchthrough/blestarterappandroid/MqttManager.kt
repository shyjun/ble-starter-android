package com.punchthrough.blestarterappandroid

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber

object MqttManager {
    private const val BROKER_URL = "tcp://broker.emqx.io:1883"
    private var client: MqttClient? = null

    fun connect() {
        try {
            client?.disconnect()
            client?.close()
        } catch (_: Exception) {}

        try {
            val mqttClient = MqttClient(BROKER_URL, clientId(), MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
            }
            mqttClient.connect(options)
            client = mqttClient
            Timber.i("MQTT connected to $BROKER_URL")
        } catch (e: MqttException) {
            Timber.e(e, "MQTT connection failed")
        }
    }

    fun publish(topic: String, payload: String) {
        client?.let {
            try {
                it.publish(topic, payload.toByteArray(), 1, false)
                Timber.i("MQTT published to $topic: $payload")
            } catch (e: MqttException) {
                Timber.e(e, "MQTT publish failed")
            }
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
            client?.close()
            client = null
        } catch (_: Exception) {}
    }

    private fun clientId(): String {
        return "ble_starter_${System.currentTimeMillis()}"
    }
}
