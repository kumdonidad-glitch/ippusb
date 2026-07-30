package com.kumdonidad.ippusb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File

/**
 * CanonDelivery: delivery façade for PWG files.
 * - Prefer CHMP-over-USB (CanonChmpClient.postDocument)
 * - Fallback to raw bulk transfer (UsbBackend.sendToPrinter)
 *
 * Returns a Result object describing success/failure and diagnostic text.
 */
object CanonDelivery {
    private const val TAG = "CanonDelivery"

    data class DeliveryResult(val ok: Boolean, val message: String)

    fun tryDeliverPwg(context: Context, pwgFile: File): DeliveryResult {
        try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = manager.deviceList
            if (devices.isEmpty()) return DeliveryResult(false, "no-usb-devices")

            // Try to find a printer-class device and a device with 'Canon' in product (best-effort)
            var chosen: UsbDevice? = null
            for ((_, d) in devices) {
                // prefer devices where productName or deviceName contains 'Canon' (if available)
                val name = d.deviceName ?: ""
                if (name.contains("canon", true) || name.contains("Canon", true)) { chosen = d; break }
                if (chosen == null) chosen = d
            }
            if (chosen == null) return DeliveryResult(false, "no-printer-interface-found")

            // Ensure we have permission
            if (!manager.hasPermission(chosen)) {
                Log.w(TAG, "No permission for device ${chosen.deviceName}")
                return DeliveryResult(false, "no-permission-for-device:${chosen.deviceName}")
            }

            // First attempt: CHMP/HTTP-over-USB
            try {
                val client = CanonChmpClient(context)
                val fis = pwgFile.inputStream()
                val res = client.postDocument(chosen, fis, "application/octet-stream")
                fis.close()
                if (res.ok) {
                    Log.i(TAG, "Delivered via CHMP to ${chosen.deviceName}")
                    return DeliveryResult(true, "chmp-ok:${res.body.substring(0, Math.min(200, res.body.length))}")
                } else {
                    Log.w(TAG, "CHMP delivery failed: ${res.body}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "CHMP delivery exception", e)
            }

            // Fallback: raw USB bulk transfer
            try {
                UsbBackend.sendToPrinter(context, pwgFile)
                Log.i(TAG, "Attempted raw USB bulkTransfer to ${chosen.deviceName}")
                return DeliveryResult(true, "bulk-transfer-attempted")
            } catch (e: Exception) {
                Log.e(TAG, "Raw USB transfer failed", e)
                return DeliveryResult(false, "both-delivery-methods-failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Canonical delivery error", e)
            return DeliveryResult(false, "exception:${e.message}")
        }
    }
}
