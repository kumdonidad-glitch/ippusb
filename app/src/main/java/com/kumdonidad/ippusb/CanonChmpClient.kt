package com.kumdonidad.ippusb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * CanonChmpClient: improved handling of chunked responses and read loop.
 * - Attempts to read until a terminating zero chunk ("0\r\n\r\n") is observed
 *   or until a read timeout/limit is reached.
 * - Decodes chunked transfer encoding into a contiguous payload returned in Result.body.
 */
class CanonChmpClient(private val context: Context) {
    companion object { private const val TAG = "CanonChmpClient" }

    data class Result(val ok: Boolean, val body: String)

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

    private fun readUntilTerminator(connection: UsbDeviceConnection, inEp: UsbEndpoint, timeoutMs: Int = 5000): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var attempts = 0
        while (attempts < 20) { // avoid infinite loops; ~20*timeoutMs total
            val read = connection.bulkTransfer(inEp, buf, buf.size, timeoutMs)
            if (read > 0) {
                out.write(buf, 0, read)
                val soFar = out.toByteArray()
                // check for chunked terminator sequence: "\r\n0\r\n\r\n"
                if (containsSequence(soFar, "\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))) {
                    break
                }
                // also break if response contains closing '</cmd>' XML end (practical heuristic)
                if (containsSequence(soFar, "</cmd>".toByteArray(Charsets.ISO_8859_1))) {
                    break
                }
            } else {
                attempts++
            }
        }
        return out.toByteArray()
    }

    private fun containsSequence(data: ByteArray, seq: ByteArray): Boolean {
        if (seq.isEmpty()) return true
        outer@ for (i in 0..(data.size - seq.size)) {
            for (j in seq.indices) if (data[i + j] != seq[j]) continue@outer
            return true
        }
        return false
    }

    private fun decodeChunkedResponse(raw: ByteArray): ByteArray {
        // Find header/body split (\r\n\r\n)
        val sep = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        var idx = -1
        outer@ for (i in 0..(raw.size - sep.size)) {
            for (j in sep.indices) if (raw[i + j] != sep[j]) continue@outer
            idx = i + sep.size
            break
        }
        if (idx < 0) return raw // no headers found; return raw

        val body = raw.copyOfRange(idx, raw.size)
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < body.size) {
            // read chunk size line up to CRLF
            var eol = -1
            for (i in pos until body.size - 1) {
                if (body[i] == '\r'.code.toByte() && body[i + 1] == '\n'.code.toByte()) { eol = i; break }
            }
            if (eol == -1) break
            val line = String(body, pos, eol - pos, Charsets.US_ASCII).trim()
            val chunkSize = try { Integer.parseInt(line.trim(), 16) } catch (_: Exception) { break }
            pos = eol + 2
            if (chunkSize == 0) break
            if (pos + chunkSize > body.size) break
            out.write(body, pos, chunkSize)
            pos += chunkSize
            // skip CRLF after chunk
            if (pos + 1 <= body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) pos += 2
        }
        return out.toByteArray()
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

            val raw = readUntilTerminator(connection, inEp, 3000)
            if (raw.isNotEmpty()) {
                val decoded = try { decodeChunkedResponse(raw) } catch (e: Exception) { raw }
                val body = String(decoded, Charsets.ISO_8859_1)
                Log.i(TAG, "read ${decoded.size} decoded bytes")
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

            val raw = readUntilTerminator(connection, inEp, 5000)
            if (raw.isNotEmpty()) {
                val decoded = try { decodeChunkedResponse(raw) } catch (e: Exception) { raw }
                val body = String(decoded, Charsets.ISO_8859_1)
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
