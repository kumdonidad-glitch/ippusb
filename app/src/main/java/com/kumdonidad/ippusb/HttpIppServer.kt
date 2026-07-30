package com.kumdonidad.ippusb

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HttpIppServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    companion object { private const val TAG = "HttpIppServer" }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        try {
            when (session.method) {
                Method.GET -> {
                    if (uri == "/") return newFixedLengthResponse(Response.Status.OK, "text/plain", "IPP USB server")
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
                Method.POST -> {
                    // Stream request body directly to temp file to avoid OOM
                    val jobsDir = File(context.filesDir, "jobs")
                    if (!jobsDir.exists()) jobsDir.mkdirs()
                    val time = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(Date())
                    val tmpFile = File(jobsDir, "job-$time.tmp")

                    try {
                        session.inputStream.use { input ->
                            FileOutputStream(tmpFile).use { fos ->
                                copyStream(input, fos)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error streaming request to file", e)
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error saving job: ${e.message}")
                    }

                    val contentType = session.headers["content-type"] ?: ""
                    Log.i(TAG, "Saved raw job to ${tmpFile.absolutePath} (content-type=$contentType)")

                    // Try to detect embedded PDF by scanning the file for "%PDF-"
                    val pdfFile = File(jobsDir, "job-$time.pdf")
                    val pwgFile = File(jobsDir, "job-$time.pwg")

                    val foundPdf = extractPdfIfPresent(tmpFile, pdfFile)

                    if (foundPdf || contentType.contains("application/pdf", ignoreCase = true)) {
                        // If content-type is application/pdf and extraction failed, treat entire file as PDF
                        if (!foundPdf && contentType.contains("application/pdf", ignoreCase = true)) {
                            tmpFile.copyTo(pdfFile, overwrite = true)
                        }

                        val ok = PWGRasterEncoder.pdfToPwgAllPages(context, pdfFile, pwgFile)
                        if (ok) {
                            Log.i(TAG, "Converted PDF -> PWG: ${pwgFile.absolutePath}")
                            val res = CanonDelivery.tryDeliverPwg(context, pwgFile)
                            return newFixedLengthResponse(Response.Status.OK, "text/plain", "PDF job queued: ${res.message}")
                        } else {
                            Log.w(TAG, "Failed to convert PDF to PWG")
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to convert PDF to PWG")
                        }
                    }

                    // For other POSTs under /ipp, save and acknowledge. We deliberately do NOT claim
                    // full IPP protocol compatibility yet. We only save the raw payload for analysis.
                    if (uri.startsWith("/ipp")) {
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", "IPP payload saved (raw)")
                    }

                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
                else -> return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun copyStream(input: InputStream, fos: FileOutputStream) {
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n >= 0) {
            if (n > 0) fos.write(buf, 0, n)
            n = input.read(buf)
        }
    }

    private fun extractPdfIfPresent(src: File, outPdf: File): Boolean {
        val magic = "%PDF-".toByteArray(Charsets.US_ASCII)
        val chunk = ByteArray(8192)
        var offset = 0L
        src.inputStream().use { fis ->
            var read = fis.read(chunk)
            var buffer = ByteArrayOutputStream()
            while (read >= 0) {
                if (read > 0) buffer.write(chunk, 0, read)
                val data = buffer.toByteArray()
                val idx = indexOf(data, magic)
                if (idx >= 0) {
                    // write from idx to end to outPdf
                    outPdf.outputStream().use { os ->
                        os.write(data, idx, data.size - idx)
                        // now stream rest of fis to os
                        var rest = fis.read(chunk)
                        while (rest > 0) { os.write(chunk, 0, rest); rest = fis.read(chunk) }
                        os.flush()
                    }
                    return true
                }
                // keep last magic.size-1 bytes in buffer for cross-boundary matches
                if (data.size > magic.size) {
                    val keep = data.copyOfRange(data.size - magic.size, data.size)
                    buffer.reset()
                    buffer.write(keep)
                }
                read = fis.read(chunk)
            }
        }
        return false
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty()) return 0
        outer@ for (i in 0..(data.size - pattern.size)) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
