package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import ai.inmo.openclaw.ui.capture.ScreenCaptureCoordinator
import android.content.Context
import java.io.File
import java.util.Base64

class ScreenCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "screen"
    override val commands: List<String> = listOf("screen.record")

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        if (command != "screen.record") {
            return error("UNKNOWN_COMMAND", "Unknown screen command: $command")
        }
        val durationMs = (params["durationMs"] as? Number)?.toLong() ?: 5000L
        return runCatching {
            val filePath = ScreenCaptureCoordinator.requestCapture(context, durationMs)
                ?: return error("SCREEN_DENIED", "User denied screen recording")
            val file = File(filePath)
            if (!file.exists()) return error("SCREEN_ERROR", "Recording file not found")
            val bytes = file.readBytes()
            file.delete()
            NodeFrame.response("", payload = mapOf(
                "base64" to Base64.getEncoder().encodeToString(bytes),
                "format" to "mp4"
            ))
        }.getOrElse { error("SCREEN_ERROR", it.message ?: "Screen recording failed") }
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
