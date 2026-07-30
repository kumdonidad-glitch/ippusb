package com.kumdonidad.ippusb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.InputStream
import java.util.UUID

/**
 * Very small CHMP/HTTP-over-USB client for Canon devices observed in the capture.
 * This is a best-effort scaffold: it opens the USB printer-class interface and
 * performs basic GET/POST exchanges using chunked encoding similar to what's
 * present in your pcap (X-CHMP-* headers).
 */
class CanonChmpClient(private val context: Context) {
    companion object { private const val TAG = "CanonChmpClient" }

    data class Result(val ok: Boolean, val body: String)

    private fun openConnection(device: UsbDevice): Triple<UsbDeviceConnection, UsbInterface, UsbEndpoint>? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(device) ?: return null
        var foundInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                foundInterface = intf
                break
            }
        }
        if (foundInterface == null) {
            connection.close()
            return null
        }
        if (!connection.claimInterface(foundInterface, true)) {
            connection.close()
            return null
        }
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        for (i in 0 until foundInterface.endpointCount) {
            val ep = foundInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
            }
        }
        if (outEp == null || inEp == null) {
            connection.releaseInterface(foundInterface)
            connection.close()
            return null
        }
        return Triple(connection, foundInterface, outEp).also { /* inEp returned separately by read method */ }
    }

    private fun findPrinterInterface(device: UsbDevice): Pair<UsbInterface, Pair<UsbEndpoint?, UsbEndpoint?>>? {
        var printerInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                printerInterface = intf
                break
            }
        }
        if (printerInterface == null) return null
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        for (i in 0 until printerInterface.endpointCount) {
            val ep = printerInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
            }
        }
        return Pair(printerInterface, Pair(inEp, outEp))
    }

    private fun openDeviceConnection(device: UsbDevice): Triple<UsbDeviceConnection, UsbInterface, Pair<UsbEndpoint, UsbEndpoint>>? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(device) ?: return null
        val tup = findPrinterInterface(device) ?: run { connection.close(); return null }
        val intf = tup.first
        val endpoints = tup.second
        if (!connection.claimInterface(intf, true)) { connection.close(); return null }
        val inEp = endpoints.first ?: run { connection.releaseInterface(intf); connection.close(); return null }
        val outEp = endpoints.second ?: run { connection.releaseInterface(intf); connection.close(); return null }
        return Triple(connection, intf, Pair(inEp, outEp))
    }

    fun getCapability(device: UsbDevice): Result {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) return Result(false, "no-permission")
        val open = openDeviceConnection(device) ?: return Result(false, "open-failed")
        val connection = open.first
        val intf = open.second
        val inEp = open.third.first
        val outEp = open.third.second
        try {
            val sessionId = "{" + UUID.randomUUID().toString().uppercase() + "}"
            val request = buildString {
                append("POST /canon/ij/command2/port1 HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: Keep-Alive\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Transfer-Encoding: chunked\r\n")
                append("X-CHMP-Version: 1.3.0\r\n")
                append("X-CHMP-Session: $sessionId\r\n")
                append("\r\n")
                // one small zero-length chunk to trigger a capability response
                append("0\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            val sent = connection.bulkTransfer(outEp, request, request.size, 5000)
            Log.i(TAG, "sent $sent bytes to OUT endpoint")

            // read response (non-blocking loop)
            val respBuf = ByteArray(16384)
            val read = connection.bulkTransfer(inEp, respBuf, respBuf.size, 5000)
            if (read > 0) {
                val body = String(respBuf, 0, read, Charsets.ISO_8859_1)
                Log.i(TAG, "read $read bytes")
                return Result(true, body)
            }
            return Result(false, "no-response")
        } catch (e: Exception) {
            Log.e(TAG, "error talking to device", e)
            return Result(false, e.message ?: "error")
        } finally {
            try { connection.releaseInterface(intf) } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
    }

    /**
     * Post an already-encoded PWG/RAW payload to the Canon using chunked transfer.
     * payload stream will be read in chunks and each chunk sent as chunked body.
     */
    fun postDocument(device: UsbDevice, payload: InputStream, contentType: String = "application/octet-stream"): Result {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) return Result(false, "no-permission")
        val open = openDeviceConnection(device) ?: return Result(false, "open-failed")
        val connection = open.first
        val intf = open.second
        val inEp = open.third.first
        val outEp = open.third.second
        try {
            val sessionId = "{" + UUID.randomUUID().toString().uppercase() + "}"
            val headers = buildString {
                append("POST /canon/ij/command2/port1 HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: Keep-Alive\r\n")
                append("Content-Type: $contentType\r\n")
                append("Transfer-Encoding: chunked\r\n")
                append("X-CHMP-Version: 1.3.0\r\n")
                append("X-CHMP-Session: $sessionId\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)

            var sent = connection.bulkTransfer(outEp, headers, headers.size, 5000)
            Log.i(TAG, "sent headers $sent")

            val buf = ByteArray(8192)
            var n = payload.read(buf)
            while (n > 0) {
                val chunkSizeLine = Integer.toHexString(n)
                val chunkHeader = (chunkSizeLine + "\r\n").toByteArray(Charsets.US_ASCII)
                sent = connection.bulkTransfer(outEp, chunkHeader, chunkHeader.size, 5000)
                if (sent < 0) break
                sent = connection.bulkTransfer(outEp, buf, n, 5000)
                if (sent < 0) break
                val crlf = "\r\n".toByteArray(Charsets.US_ASCII)
                sent = connection.bulkTransfer(outEp, crlf, crlf.size, 5000)
                if (sent < 0) break
                n = payload.read(buf)
            }
            // final zero chunk
            val last = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)
            sent = connection.bulkTransfer(outEp, last, last.size, 5000)
            Log.i(TAG, "sent final chunk $sent")

            // read response
            val respBuf = ByteArray(32768)
            val read = connection.bulkTransfer(inEp, respBuf, respBuf.size, 10000)
            if (read > 0) {
                val body = String(respBuf, 0, read, Charsets.ISO_8859_1)
                return Result(true, body)
            }
            return Result(false, "no-response")
        } catch (e: Exception) {
            Log.e(TAG, "error posting document", e)
            return Result(false, e.message ?: "error")
        } finally {
            try { connection.releaseInterface(intf) } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
    }
}
