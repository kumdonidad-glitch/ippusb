package com.kumdonidad.ippusb

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
                    // Read entire request body into memory (stream to file for large payloads)
                    val baos = ByteArrayOutputStream()
                    session.inputStream.copyTo(baos)
                    val bodyBytes = baos.toByteArray()

                    val contentType = session.headers["content-type"] ?: ""

                    // Save raw job for debugging
                    val jobsDir = File(context.filesDir, "jobs")
                    if (!jobsDir.exists()) jobsDir.mkdirs()
                    val time = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(Date())
                    val jobFile = File(jobsDir, "job-$time.bin")
                    FileOutputStream(jobFile).use { it.write(bodyBytes) }
                    Log.i(TAG, "Saved raw job to ${jobFile.absolutePath} (content-type=$contentType)")

                    // If this looks like IPP, try to find embedded PDF or application/pdf payload
                    if (contentType.contains("application/ipp", ignoreCase = true) || contentType.contains("application/octet-stream", ignoreCase = true)) {
                        // Search for PDF magic header (%PDF-) in the body
                        val pdfMagic = "%PDF-".toByteArray(Charsets.US_ASCII)
                        val idx = indexOf(bodyBytes, pdfMagic)
                        if (idx >= 0) {
                            val pdfBytes = bodyBytes.copyOfRange(idx, bodyBytes.size)
                            val pdfFile = File(jobsDir, "job-$time.pdf")
                            FileOutputStream(pdfFile).use { it.write(pdfBytes) }
                            Log.i(TAG, "Extracted embedded PDF to ${pdfFile.absolutePath}")

                            // Convert to PWG raster and attempt to send to USB printer if available
                            val pwgFile = File(jobsDir, "job-$time.pwg")
                            val ok = PWGRasterEncoder.pdfToPwgAllPages(context, pdfFile, pwgFile)
                            if (ok) {
                                Log.i(TAG, "Converted PDF -> PWG: ${pwgFile.absolutePath}")
                                // Attempt to send to any attached USB Canon-style printer
                                // prefer Canon CHMP client if available
                                CanonDelivery.tryDeliverPwg(context, pwgFile)
                            } else {
                                Log.w(TAG, "Failed to convert extracted PDF to PWG")
                            }

                            return newFixedLengthResponse(Response.Status.OK, "text/plain", "IPP job stored and PDF extracted and queued")
                        }
                    }

                    // If the Content-Type is plain application/pdf direct POSTs
                    if (contentType.contains("application/pdf", ignoreCase = true)) {
                        val pdfFile = File(jobsDir, "job-$time.pdf")
                        FileOutputStream(pdfFile).use { it.write(bodyBytes) }
                        val pwgFile = File(jobsDir, "job-$time.pwg")
                        val ok = PWGRasterEncoder.pdfToPwgAllPages(context, pdfFile, pwgFile)
                        if (ok) {
                            CanonDelivery.tryDeliverPwg(context, pwgFile)
                            return newFixedLengthResponse(Response.Status.OK, "text/plain", "PDF job received and queued")
                        } else {
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to convert PDF")
                        }
                    }

                    // For other POSTs under /ipp, save and acknowledge. We deliberately do NOT claim
                    // full IPP protocol compatibility yet. We only save the raw payload for analysis.
                    if (uri.startsWith("/ipp")) {
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", "IPP payload saved (raw)")
                    }

                    // fallback
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
                else -> return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
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
