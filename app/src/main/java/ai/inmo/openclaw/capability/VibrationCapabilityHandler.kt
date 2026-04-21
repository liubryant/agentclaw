package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "haptic"
    override val commands: List<String> = listOf("haptic.vibrate")

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        if (command != "haptic.vibrate") {
            return error("UNKNOWN_COMMAND", "Unknown haptic command: $command")
        }
        val durationMs = (params["durationMs"] as? Number)?.toLong() ?: 200L
        val pattern = (params["pattern"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
        return runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) {
                return error("HAPTIC_ERROR", "Device does not support vibration")
            }
            if (!pattern.isNullOrEmpty()) {
                val timings = LongArray(pattern.size) { index -> pattern[index] }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            NodeFrame.response("", payload = mapOf("status" to "vibrated"))
        }.getOrElse { error("HAPTIC_ERROR", it.message ?: "Vibration failed") }
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
