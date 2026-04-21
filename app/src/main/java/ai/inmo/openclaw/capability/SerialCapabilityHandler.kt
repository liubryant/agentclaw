package ai.inmo.openclaw.capability

import ai.inmo.openclaw.domain.model.NodeFrame
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.felhr.usbserial.UsbSerialDevice
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SerialCapabilityHandler(
    private val context: Context
) : NodeCapabilityHandler {
    override val name: String = "serial"
    override val commands: List<String> = listOf("serial.list", "serial.connect", "serial.disconnect", "serial.write", "serial.read")

    private val connections = ConcurrentHashMap<String, SerialConnection>()

    override suspend fun handle(command: String, params: Map<String, Any?>): NodeFrame {
        return when (command) {
            "serial.list" -> listDevices()
            "serial.connect" -> connect(params)
            "serial.disconnect" -> disconnect(params)
            "serial.write" -> write(params)
            "serial.read" -> read(params)
            else -> error("UNKNOWN_COMMAND", "Unknown serial command: $command")
        }
    }

    private fun listDevices(): NodeFrame {
        val devices = mutableListOf<Map<String, Any?>>()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.deviceList.values.forEach { device ->
            devices += mapOf(
                "id" to "usb:${device.deviceId}",
                "type" to "usb",
                "name" to (device.productName ?: "USB Device"),
                "vendorId" to device.vendorId,
                "productId" to device.productId,
                "permissionGranted" to usbManager.hasPermission(device)
            )
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && hasBluetoothPermission()) {
            adapter.bondedDevices.orEmpty().forEach { device ->
                devices += mapOf(
                    "id" to "bt:${device.address}",
                    "type" to "bluetooth",
                    "name" to (device.name ?: device.address)
                )
            }
        }
        return NodeFrame.response("", payload = mapOf("devices" to devices))
    }

    @SuppressLint("MissingPermission")
    private fun connect(params: Map<String, Any?>): NodeFrame {
        val deviceId = params["deviceId"]?.toString() ?: return error("MISSING_PARAM", "deviceId is required")
        if (connections.containsKey(deviceId)) {
            return NodeFrame.response("", payload = mapOf("status" to "already_connected", "deviceId" to deviceId))
        }
        return when {
            deviceId.startsWith("usb:") -> connectUsb(deviceId, params)
            deviceId.startsWith("bt:") -> connectBluetooth(deviceId)
            else -> error("INVALID_DEVICE_ID", "deviceId must start with usb: or bt:")
        }
    }

    private fun connectUsb(deviceId: String, params: Map<String, Any?>): NodeFrame {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val numericId = deviceId.removePrefix("usb:").toIntOrNull() ?: return error("INVALID_DEVICE_ID", "Invalid USB device id")
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == numericId }
            ?: return error("CONNECT_ERROR", "USB device not found")
        if (!usbManager.hasPermission(device)) {
            return error("USB_PERMISSION_REQUIRED", "USB permission must be granted by the user before connecting")
        }
        val connection = usbManager.openDevice(device) ?: return error("CONNECT_ERROR", "Unable to open USB device")
        val serial = UsbSerialDevice.createUsbSerialDevice(device, connection)
            ?: return error("CONNECT_ERROR", "Unable to create USB serial device")
        if (!serial.open()) {
            connection.close()
            return error("CONNECT_ERROR", "Unable to open USB serial port")
        }
        val baudRate = (params["baudRate"] as? Number)?.toInt() ?: 115200
        serial.setBaudRate(baudRate)
        serial.setDataBits(UsbSerialDevice.DATA_BITS_8)
        serial.setStopBits(UsbSerialDevice.STOP_BITS_1)
        serial.setParity(UsbSerialDevice.PARITY_NONE)
        serial.setFlowControl(UsbSerialDevice.FLOW_CONTROL_OFF)
        val serialConnection = UsbSerialConnection(device, connection, serial)
        serial.read { bytes -> serialConnection.append(bytes) }
        connections[deviceId] = serialConnection
        return NodeFrame.response("", payload = mapOf("status" to "connected", "deviceId" to deviceId, "type" to "usb", "baudRate" to baudRate))
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth(deviceId: String): NodeFrame {
        if (!hasBluetoothPermission()) {
            return error("PERMISSION_DENIED", "Bluetooth permission not granted")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return error("CONNECT_ERROR", "Bluetooth not supported")
        val address = deviceId.removePrefix("bt:")
        val device = adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
            ?: return error("CONNECT_ERROR", "Bluetooth device not found")
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        return runCatching {
            socket.connect()
            connections[deviceId] = BluetoothSerialConnection(device, socket)
            NodeFrame.response("", payload = mapOf("status" to "connected", "deviceId" to deviceId, "type" to "bluetooth"))
        }.getOrElse {
            runCatching { socket.close() }
            error("CONNECT_ERROR", it.message ?: "Bluetooth connection failed")
        }
    }

    private fun disconnect(params: Map<String, Any?>): NodeFrame {
        val deviceId = params["deviceId"]?.toString() ?: return error("MISSING_PARAM", "deviceId is required")
        val connection = connections.remove(deviceId)
            ?: return NodeFrame.response("", payload = mapOf("status" to "not_connected", "deviceId" to deviceId))
        connection.close()
        return NodeFrame.response("", payload = mapOf("status" to "disconnected", "deviceId" to deviceId))
    }

    private fun write(params: Map<String, Any?>): NodeFrame {
        val deviceId = params["deviceId"]?.toString() ?: return error("MISSING_PARAM", "deviceId is required")
        val data = params["data"]?.toString() ?: return error("MISSING_PARAM", "data is required")
        val connection = connections[deviceId] ?: return error("NOT_CONNECTED", "Device not connected: $deviceId")
        return runCatching {
            val bytes = data.toByteArray(Charset.forName("UTF-8"))
            connection.write(bytes)
            NodeFrame.response("", payload = mapOf("status" to "written", "deviceId" to deviceId, "bytesWritten" to bytes.size))
        }.getOrElse { error("WRITE_ERROR", it.message ?: "Write failed") }
    }

    private fun read(params: Map<String, Any?>): NodeFrame {
        val deviceId = params["deviceId"]?.toString() ?: return error("MISSING_PARAM", "deviceId is required")
        val connection = connections[deviceId] ?: return error("NOT_CONNECTED", "Device not connected: $deviceId")
        return runCatching {
            val bytes = connection.read()
            NodeFrame.response("", payload = mapOf(
                "deviceId" to deviceId,
                "data" to bytes?.toString(Charset.forName("UTF-8")),
                "bytesRead" to (bytes?.size ?: 0)
            ))
        }.getOrElse { error("READ_ERROR", it.message ?: "Read failed") }
    }

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun error(code: String, message: String): NodeFrame {
        return NodeFrame.response("", error = mapOf("code" to code, "message" to message))
    }

    private interface SerialConnection {
        fun write(bytes: ByteArray)
        fun read(): ByteArray?
        fun close()
    }

    private class UsbSerialConnection(
        private val device: UsbDevice,
        private val connection: UsbDeviceConnection,
        private val serialDevice: UsbSerialDevice
    ) : SerialConnection {
        private val buffer = mutableListOf<Byte>()

        fun append(bytes: ByteArray) {
            synchronized(buffer) { buffer.addAll(bytes.toList()) }
        }

        override fun write(bytes: ByteArray) {
            serialDevice.write(bytes)
        }

        override fun read(): ByteArray? {
            synchronized(buffer) {
                if (buffer.isEmpty()) return null
                val copy = buffer.toByteArray()
                buffer.clear()
                return copy
            }
        }

        override fun close() {
            serialDevice.close()
            connection.close()
        }
    }

    private class BluetoothSerialConnection(
        private val device: BluetoothDevice,
        private val socket: BluetoothSocket
    ) : SerialConnection {
        override fun write(bytes: ByteArray) {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }

        override fun read(): ByteArray? {
            val input = socket.inputStream
            if (input.available() <= 0) return null
            val bytes = ByteArray(input.available())
            val count = input.read(bytes)
            return if (count > 0) bytes.copyOf(count) else null
        }

        override fun close() {
            socket.close()
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
