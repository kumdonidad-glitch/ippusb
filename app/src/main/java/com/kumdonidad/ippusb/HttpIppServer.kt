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

                    // If this is an IPP endpoint, dispatch to the IPP handler which will
                    // parse the IPP header, handle Get-Printer-Attributes and Print-Job,
                    // and perform PDF -> PWG conversion + delivery as needed.
                    if (uri.startsWith("/ipp")) {
                        val hostHeader = session.headers["host"]
                        return IppHandler.handleIppRequest(context, tmpFile, hostHeader)
                    }

                    // For other POSTs, save and acknowledge.
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
}
