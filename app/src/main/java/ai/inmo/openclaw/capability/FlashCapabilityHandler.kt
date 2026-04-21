package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat

class FlashCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "flash"
    override val commands: List<String> = listOf("flash.on", "flash.off", "flash.toggle", "flash.status")

    private var torchOn = false

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return error("PERMISSION_DENIED", "Camera permission not granted")
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return error("FLASH_ERROR", "No rear torch camera available")

        return when (command) {
            "flash.on" -> setTorch(cameraManager, cameraId, true)
            "flash.off" -> setTorch(cameraManager, cameraId, false)
            "flash.toggle" -> setTorch(cameraManager, cameraId, !torchOn)
            "flash.status" -> NodeFrame.response("", payload = mapOf("on" to torchOn))
            else -> error("UNKNOWN_COMMAND", "Unknown flash command: $command")
        }
    }

    private fun setTorch(cameraManager: CameraManager, cameraId: String, enabled: Boolean): NodeFrame {
        return runCatching {
            cameraManager.setTorchMode(cameraId, enabled)
            torchOn = enabled
            NodeFrame.response("", payload = mapOf("on" to torchOn))
        }.getOrElse { error("FLASH_ERROR", it.message ?: "Torch update failed") }
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
