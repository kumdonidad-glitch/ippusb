package com.kumdonidad.ippusb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

object UsbBackend {
    private const val TAG = "UsbBackend"

    fun sendToPrinter(context: Context, jobFile: File) {
        thread(start = true) {
            try {
                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val devices = manager.deviceList
                if (devices.isEmpty()) {
                    Log.w(TAG, "No USB devices found to send job")
                    return@thread
                }

                // Find first printer-class interface (bInterfaceClass == 7)
                var found: Pair<UsbDevice, UsbInterface>? = null
                for ((_, device) in devices) {
                    for (i in 0 until device.interfaceCount) {
                        val intf = device.getInterface(i)
                        if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                            found = Pair(device, intf)
                            break
                        }
                    }
                    if (found != null) break
                }

                if (found == null) {
                    Log.w(TAG, "No USB printer interfaces found")
                    return@thread
                }

                val device = found.first
                val intf = found.second!!

                // IMPORTANT: the app must have permission to access the device.
                if (!manager.hasPermission(device)) {
                    Log.w(TAG, "Missing permission for device ${device.deviceName}")
                    // In a real app, request permission from an Activity. Here we just log.
                    return@thread
                }

                val connection: UsbDeviceConnection? = manager.openDevice(device)
                if (connection == null) {
                    Log.w(TAG, "Failed to open connection")
                    return@thread
                }

                if (!connection.claimInterface(intf, true)) {
                    Log.w(TAG, "Failed to claim interface")
                    connection.close()
                    return@thread
                }

                // Find bulk out endpoint
                var outEndpoint: UsbEndpoint? = null
                for (eIndex in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(eIndex)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep
                        break
                    }
                }

                if (outEndpoint == null) {
                    Log.w(TAG, "No bulk OUT endpoint found")
                    connection.releaseInterface(intf)
                    connection.close()
                    return@thread
                }

                // Stream the file in chunks
                val buffer = ByteArray(16384)
                val fis = jobFile.inputStream()
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    var offset = 0
                    while (offset < read) {
                        val chunk = buffer.copyOfRange(offset, read)
                        val sent = connection.bulkTransfer(outEndpoint, chunk, chunk.size, 5000)
                        if (sent < 0) {
                            Log.w(TAG, "bulkTransfer returned $sent")
                            break
                        }
                        offset += sent
                    }
                }
                fis.close()

                connection.releaseInterface(intf)
                connection.close()

                Log.i(TAG, "Job sent to printer: ${jobFile.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending to USB printer", e)
            }
        }
    }
}
