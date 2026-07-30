package com.kumdonidad.ippusb

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream

class HttpIppServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    companion object { private const val TAG = "HttpIppServer" }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        when (session.method) {
            Method.GET -> {
                if (uri == "/") return newFixedLengthResponse(Response.Status.OK, "text/plain", "IPP USB server")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            Method.POST -> {
                // Very basic handling: accept POST to /ipp/printer1 and save body
                if (uri.startsWith("/ipp")) {
                    try {
                        val tmp = File(context.filesDir, "jobs")
                        if (!tmp.exists()) tmp.mkdirs()
                        val jobFile = File.createTempFile("job-", ".bin", tmp)
                        val fos = FileOutputStream(jobFile)
                        session.inputStream.copyTo(fos)
                        fos.close()

                        // Forward to USB backend (non-blocking)
                        UsbBackend.sendToPrinter(context, jobFile)

                        return newFixedLengthResponse(Response.Status.OK, "application/ipp", "\u0000\u0000\u0000\u01")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving job", e)
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            else -> return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
        }
    }
}
