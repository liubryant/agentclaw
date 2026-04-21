package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat

class CameraCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "camera"
    override val commands: List<String> = listOf("camera.list")

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        if (command != "camera.list") {
            return error("NOT_SUPPORTED", "Camera capture requires an interactive Activity flow and is not wired yet.")
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return error("PERMISSION_DENIED", "Camera permission not granted")
        }
        return runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameras = manager.cameraIdList.map { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                mapOf(
                    "id" to id,
                    "facing" to facing
                )
            }
            NodeFrame.response("", payload = mapOf("cameras" to cameras))
        }.getOrElse { error("CAMERA_ERROR", it.message ?: "Failed to list cameras") }
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
