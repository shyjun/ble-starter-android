package com.punchthrough.blestarterappandroid

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber

object MqttManager {
    private const val BROKER_URL = "tcp://broker.emqx.io:1883"
    private var client: MqttClient? = null
    var onMessageReceived: ((topic: String, payload: String) -> Unit)? = null

    fun connect() {
        try {
            client?.disconnect()
            client?.close()
        } catch (_: Exception) {}

        try {
            val mqttClient = MqttClient(BROKER_URL, clientId(), MemoryPersistence())

            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Timber.w(cause, "MQTT connection lost")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload, Charsets.UTF_8)
                    Timber.i("MQTT received on $topic: $payload")
                    onMessageReceived?.invoke(topic, payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Timber.i("MQTT delivery complete")
                }
            })

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

    fun subscribe(topic: String) {
        client?.let {
            try {
                it.subscribe(topic)
                Timber.i("MQTT subscribed to $topic")
            } catch (e: MqttException) {
                Timber.e(e, "MQTT subscribe failed")
            }
        }
    }

    fun disconnect() {
        onMessageReceived = null
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
