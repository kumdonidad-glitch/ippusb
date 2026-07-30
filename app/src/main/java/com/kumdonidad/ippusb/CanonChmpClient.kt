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
 * CanonChmpClient: further hardened handling of chunked responses and read loop.
 * Improvements:
 * - Handles chunk-size lines that may be split across USB bulkTransfer boundaries.
 * - Configurable per-call timeouts and maximum bytes to prevent unbounded reads.
 * - Single retry on transient transport failure.
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

    private fun readUntilTerminator(connection: UsbDeviceConnection, inEp: UsbEndpoint, readTimeoutMs: Int, totalMaxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunkBuf = ByteArray(16384)
        var leftover = ByteArray(0)
        var totalRead = 0
        var attempts = 0
        while (attempts < 60 && totalRead < totalMaxBytes) { // ~60 read attempts maximum
            val read = connection.bulkTransfer(inEp, chunkBuf, chunkBuf.size, readTimeoutMs)
            if (read > 0) {
                totalRead += read
                // combine leftover + new read into buffer to allow cross-boundary parsing
                val combined = ByteArray(leftover.size + read)
                System.arraycopy(leftover, 0, combined, 0, leftover.size)
                System.arraycopy(chunkBuf, 0, combined, leftover.size, read)

                out.write(combined)

                // compute new leftover: keep last 128 bytes for cross-boundary patterns
                val keep = Math.min(128, combined.size)
                leftover = combined.copyOfRange(combined.size - keep, combined.size)

                // check for terminators in the constructed output
                val soFar = out.toByteArray()
                if (containsSequence(soFar, "\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))) break
                if (containsSequence(soFar, "</cmd>".toByteArray(Charsets.ISO_8859_1))) break
                attempts = 0 // reset attempts on successful read
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
        var leftoverLine = ""
        while (pos < body.size) {
            // Read up to CRLF for the chunk-size line, but allow for leftover from previous iteration
            var eol = -1
            for (i in pos until body.size - 1) {
                if (body[i] == '\r'.code.toByte() && body[i + 1] == '\n'.code.toByte()) { eol = i; break }
            }
            if (eol == -1) break
            val line = leftoverLine + String(body, pos, eol - pos, Charsets.US_ASCII)
            leftoverLine = ""
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

    fun getCapability(device: UsbDevice, readTimeoutMs: Int = 3000, totalMaxBytes: Int = 256 * 1024): Result {
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
                append("0\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            var sent = connection.bulkTransfer(outEp, request, request.size, 5000)
            Log.i(TAG, "sent $sent bytes to OUT endpoint (getCapability)")

            // Read with a single retry on transient read failures
            var raw = readUntilTerminator(connection, inEp, readTimeoutMs, totalMaxBytes)
            if (raw.isEmpty()) {
                // retry once
                raw = readUntilTerminator(connection, inEp, readTimeoutMs * 2, totalMaxBytes)
            }
            if (raw.isNotEmpty()) {
                val decoded = try { decodeChunkedResponse(raw) } catch (e: Exception) { raw }
                val body = String(decoded, Charsets.ISO_8859_1)
                Log.i(TAG, "getCapability read ${decoded.size} decoded bytes")
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

    fun postDocument(device: UsbDevice, payload: InputStream, contentType: String = "application/octet-stream", readTimeoutMs: Int = 5000, totalMaxBytes: Int = 1024 * 1024): Result {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) return Result(false, "no-permission")
        val open = openDeviceConnection(device) ?: return Result(false, "open-failed")
        val connection = open.first
        val intf = open.second
        val inEp = open.third.first
        val outEp = open.third.second
        var attempt = 0
        while (attempt < 2) {
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
                Log.i(TAG, "sent headers $sent (postDocument)")

                val buf = ByteArray(8192)
                var n = payload.read(buf)
                while (n > 0) {
                    val chunkSizeLine = Integer.toHexString(n)
                    val chunkHeader = (chunkSizeLine + "\r\n").toByteArray(Charsets.US_ASCII)
                    sent = connection.bulkTransfer(outEp, chunkHeader, chunkHeader.size, 5000)
                    if (sent < 0) throw Exception("bulkTransfer-out failed (chunkHeader)")
                    sent = connection.bulkTransfer(outEp, buf, n, 5000)
                    if (sent < 0) throw Exception("bulkTransfer-out failed (chunkBody)")
                    val crlf = "\r\n".toByteArray(Charsets.US_ASCII)
                    sent = connection.bulkTransfer(outEp, crlf, crlf.size, 5000)
                    if (sent < 0) throw Exception("bulkTransfer-out failed (crlf)")
                    n = payload.read(buf)
                }
                // final zero chunk
                val last = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                sent = connection.bulkTransfer(outEp, last, last.size, 5000)
                Log.i(TAG, "sent final chunk $sent")

                val raw = readUntilTerminator(connection, inEp, readTimeoutMs, totalMaxBytes)
                if (raw.isNotEmpty()) {
                    val decoded = try { decodeChunkedResponse(raw) } catch (e: Exception) { raw }
                    val body = String(decoded, Charsets.ISO_8859_1)
                    return Result(true, body)
                }
                return Result(false, "no-response")
            } catch (e: Exception) {
                Log.e(TAG, "error posting document (attempt=$attempt)", e)
                attempt++
                Thread.sleep(500)
                // try again
                continue
            } finally {
                try { connection.releaseInterface(intf) } catch (_: Exception) {}
                try { connection.close() } catch (_: Exception) {}
            }
        }
        return Result(false, "failed-after-retries")
    }
}
