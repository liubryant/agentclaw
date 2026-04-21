package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class LocationCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "location"
    override val commands: List<String> = listOf("location.get")

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        if (command != "location.get") {
            return error("UNKNOWN_COMMAND", "Unknown location command: $command")
        }
        if (!hasPermission()) {
            return error("PERMISSION_DENIED", "Location permission not granted")
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return error("LOCATION_DISABLED", "Location services are disabled")
        }

        val location = fetchLocation(manager) ?: return error("LOCATION_UNAVAILABLE", "No location fix available")
        return NodeFrame.response(
            "",
            payload = mapOf(
                "lat" to location.latitude,
                "lng" to location.longitude,
                "accuracy" to location.accuracy,
                "altitude" to location.altitude,
                "timestamp" to location.time
            )
        )
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(manager: LocationManager): Location? {
        val lastKnown = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null) return lastKnown

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            return suspendCancellableCoroutine { continuation ->
                manager.getCurrentLocation(provider, null, Executors.newSingleThreadExecutor()) { location ->
                    continuation.resume(location)
                }
            }
        }
        return null
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }
}
