package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SensorCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "sensor"
    override val commands: List<String> = listOf("sensor.read", "sensor.list")

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        return when (command) {
            "sensor.list" -> NodeFrame.response("", payload = mapOf("sensors" to listOf("accelerometer", "gyroscope", "magnetometer", "barometer")))
            "sensor.read" -> readSensor(params)
            else -> error("UNKNOWN_COMMAND", "Unknown sensor command: $command")
        }
    }

    private suspend fun readSensor(params: Map<String, Any?>): NodeFrame {
        val sensorName = params["sensor"]?.toString() ?: "accelerometer"
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorType = when (sensorName) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
            "barometer" -> Sensor.TYPE_PRESSURE
            else -> Sensor.TYPE_ACCELEROMETER
        }
        val sensor = sensorManager.getDefaultSensor(sensorType)
            ?: return error("SENSOR_ERROR", "Sensor $sensorName not available")

        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (resumed || event == null) return
                    resumed = true
                    sensorManager.unregisterListener(this)
                    val payload = mutableMapOf<String, Any?>(
                        "sensor" to sensorName,
                        "timestamp" to event.timestamp,
                        "accuracy" to event.accuracy
                    )
                    when (sensorName) {
                        "accelerometer", "gyroscope", "magnetometer" -> {
                            payload["x"] = event.values.getOrNull(0)?.toDouble()
                            payload["y"] = event.values.getOrNull(1)?.toDouble()
                            payload["z"] = event.values.getOrNull(2)?.toDouble()
                        }
                        "barometer" -> payload["pressure"] = event.values.getOrNull(0)?.toDouble()
                    }
                    continuation.resume(NodeFrame.response("", payload = payload))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            Thread {
                Thread.sleep(3000)
                if (!resumed) {
                    resumed = true
                    sensorManager.unregisterListener(listener)
                    continuation.resume(error("SENSOR_ERROR", "Sensor read timed out"))
                }
            }.start()
        }
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
