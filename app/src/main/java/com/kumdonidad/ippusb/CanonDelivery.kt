package com.kumdonidad.ippusb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File

/**
 * CanonDelivery: delivery façade for PWG files.
 * - Prefer CHMP-over-USB (CanonChmpClient.postDocument)
 * - Optional fallback to raw bulk transfer (UsbBackend.sendToPrinter)
 *
 * Returns a DeliveryResult with structured diagnostic fields for easier UI consumption.
 */
object CanonDelivery {
    private const val TAG = "CanonDelivery"

    data class DeliveryResult(
        val ok: Boolean,
        val method: String,            // "CHMP" | "BULK" | "NONE"
        val responseSnippet: String,   // first N chars of printer reply or empty
        val errorCode: String?         // machine-readable error code or null
    )

    /**
     * Try to deliver a PWG file to a locally-attached Canon printer.
     * @param context application context
     * @param pwgFile file to send
     * @param allowFallback when true, attempt raw bulk transfer if CHMP fails
     */
    fun tryDeliverPwg(context: Context, pwgFile: File, allowFallback: Boolean = true): DeliveryResult {
        try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices: Map<String, UsbDevice> = manager.deviceList
            if (devices.isEmpty()) return DeliveryResult(false, "NONE", "", "no-usb-devices")

            // Best-effort: prefer devices whose name contains 'canon'
            var chosen: UsbDevice? = null
            for ((_, d) in devices) {
                val name = d.deviceName ?: ""
                if (name.contains("canon", true)) { chosen = d; break }
                if (chosen == null) chosen = d
            }
            if (chosen == null) return DeliveryResult(false, "NONE", "", "no-printer-interface-found")

            if (!manager.hasPermission(chosen)) {
                Log.w(TAG, "No permission for device ${chosen.deviceName}")
                return DeliveryResult(false, "NONE", "", "no-permission-for-device:${chosen.deviceName}")
            }

            // Attempt CHMP delivery first
            try {
                val client = CanonChmpClient(context)
                pwgFile.inputStream().use { fis ->
                    val res = client.postDocument(chosen, fis, "application/octet-stream")
                    val snippet = res.body.take(400)
                    if (res.ok) {
                        Log.i(TAG, "Delivered via CHMP to ${chosen.deviceName}")
                        return DeliveryResult(true, "CHMP", snippet, null)
                    } else {
                        Log.w(TAG, "CHMP delivery returned failure: $snippet")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "CHMP delivery exception", e)
            }

            if (!allowFallback) {
                Log.i(TAG, "CHMP failed and fallback disabled")
                return DeliveryResult(false, "CHMP", "", "chmp-failed-no-fallback")
            }

            // Fallback to raw bulk transfer
            try {
                UsbBackend.sendToPrinter(context, pwgFile)
                Log.i(TAG, "Attempted raw USB bulkTransfer to ${chosen.deviceName}")
                return DeliveryResult(true, "BULK", "bulk-transfer-attempted", null)
            } catch (e: Exception) {
                Log.e(TAG, "Raw USB transfer failed", e)
                return DeliveryResult(false, "BULK", "", "both-delivery-methods-failed:${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Canonical delivery error", e)
            return DeliveryResult(false, "NONE", "", "exception:${e.message}")
        }
    }
}
