package com.kumdonidad.ippusb

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import android.content.Context
import android.util.Log

/**
 * Minimal IPP handler (subset):
 * - Parses IPP request header (version, operation id, request id)
 * - Handles Get-Printer-Attributes (op 0x000B) with a small set of attributes
 * - Handles Print-Job (op 0x0002) by extracting document bytes after end-of-attributes
 *   and forwarding PDF payloads to the CanonDelivery pipeline when detected.
 *
 * This implementation is intentionally minimal to provide basic AirPrint compatibility
 * for discovery and simple Print-Job submission. It avoids full IPP parsing.
 */
object IppHandler {
    private const val TAG = "IppHandler"

    // IPP operation IDs
    private const val OP_PRINT_JOB = 0x0002
    private const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    // IPP group tags
    private const val OPERATION_ATTRIBUTES_TAG: Byte = 0x01
    private const val JOB_ATTRIBUTES_TAG: Byte = 0x02
    private const val END_OF_ATTRIBUTES_TAG: Byte = 0x03
    private const val PRINTER_ATTRIBUTES_TAG: Byte = 0x04

    // common value-tags
    private const val VAL_TAG_TEXT_WITHOUT_LANG: Byte = 0x41
    private const val VAL_TAG_NAME_WITHOUT_LANG: Byte = 0x42
    private const val VAL_TAG_KEYWORD: Byte = 0x44
    private const val VAL_TAG_URI: Byte = 0x45
    private const val VAL_TAG_CHARSET: Byte = 0x47

    fun handleIppRequest(context: Context, tmpFile: File, hostHeader: String?): NanoHTTPD.Response {
        try {
            FileInputStream(tmpFile).use { fis ->
                val header = ByteArray(8)
                val hread = fis.read(header)
                if (hread < 8) {
                    Log.w(TAG, "IPP request too short")
                    return makeIppErrorResponse(0, 0x0001) // bad-request
                }
                val verMajor = header[0].toInt() and 0xff
                val verMinor = header[1].toInt() and 0xff
                val op = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
                val reqId = ((header[4].toInt() and 0xff) shl 24) or
                        ((header[5].toInt() and 0xff) shl 16) or
                        ((header[6].toInt() and 0xff) shl 8) or
                        (header[7].toInt() and 0xff)

                Log.i(TAG, "IPP request version=$verMajor.$verMinor op=0x${op.toString(16)} reqId=$reqId")

                return when (op) {
                    OP_GET_PRINTER_ATTRIBUTES -> handleGetPrinterAttributes(reqId, hostHeader)
                    OP_PRINT_JOB -> handlePrintJob(context, tmpFile, reqId)
                    else -> {
                        Log.w(TAG, "Unhandled IPP op: 0x${op.toString(16)}")
                        makeIppErrorResponse(reqId, 0x0400) // operation-not-supported
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling IPP request", e)
            return makeIppErrorResponse(0, 0x0400)
        }
    }

    private fun handleGetPrinterAttributes(requestId: Int, hostHeader: String?): NanoHTTPD.Response {
        val baos = ByteArrayOutputStream()
        // version 1.1
        baos.write(0x01); baos.write(0x01)
        // status code: successful-ok (0x0000)
        writeShort(baos, 0x0000)
        // request-id
        writeInt(baos, requestId)

        // operation-attributes-tag
        baos.write(OPERATION_ATTRIBUTES_TAG.toInt())
        // attributes-charset (value-tag charset)
        writeAttribute(baos, VAL_TAG_CHARSET, "attributes-charset", "utf-8")
        // attributes-natural-language (use nameWithoutLanguage + keyword)
        // use keyword tag for natural language
        writeAttribute(baos, 0x44.toByte(), "attributes-natural-language", "en")

        // printer-attributes-tag
        baos.write(PRINTER_ATTRIBUTES_TAG.toInt())
        // printer-uri-supported
        val uri = (hostHeader ?: "localhost")
        val printerUri = "ipp://$uri/ipp/printer1"
        writeAttribute(baos, VAL_TAG_URI, "printer-uri-supported", printerUri)
        // printer-name
        writeAttribute(baos, VAL_TAG_TEXT_WITHOUT_LANG, "printer-name", "IPP USB Printer")
        // printer-info
        writeAttribute(baos, VAL_TAG_TEXT_WITHOUT_LANG, "printer-info", "IPP-over-USB Canon printer proxy")
        // pdl-override-supported (as keyword) -- advertise pdf and image/pwg-raster
        writeAttribute(baos, 0x44.toByte(), "pdl-override-supported", "application/pdf")
        writeAttribute(baos, 0x44.toByte(), "pdl-override-supported", "image/pwg-raster")

        // end-of-attributes
        baos.write(END_OF_ATTRIBUTES_TAG.toInt())

        val resp = baos.toByteArray()
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/ipp",
            ByteArrayInputStream(resp), resp.size.toLong())
    }

    private fun handlePrintJob(context: Context, srcFile: File, requestId: Int): NanoHTTPD.Response {
        // Locate end-of-attributes tag (0x03) and treat remaining bytes as document
        try {
            val all = srcFile.readBytes()
            val idx = all.indexOf(END_OF_ATTRIBUTES_TAG)
            if (idx < 0) {
                Log.w(TAG, "No end-of-attributes tag in Print-Job request")
                // still ack as successful for simplicity
                return makeIppSuccessResponse(requestId)
            }
            val docStart = idx + 1
            if (docStart >= all.size) {
                Log.w(TAG, "No document payload in Print-Job request")
                return makeIppSuccessResponse(requestId)
            }
            val docBytes = all.copyOfRange(docStart, all.size)

            // Try to detect embedded PDF
            val pdfMagic = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
            val pdfIdx = indexOf(docBytes, pdfMagic)
            if (pdfIdx >= 0) {
                // save PDF and convert
                val jobsDir = File(context.filesDir, "jobs")
                if (!jobsDir.exists()) jobsDir.mkdirs()
                val pdfFile = File(jobsDir, "ipp-printjob-$requestId.pdf")
                pdfFile.writeBytes(docBytes.copyOfRange(pdfIdx, docBytes.size))
                Log.i(TAG, "Saved Print-Job PDF to ${pdfFile.absolutePath}")
                val pwgFile = File(jobsDir, "ipp-printjob-$requestId.pwg")
                val ok = PWGRasterEncoder.pdfToPwgAllPages(context, pdfFile, pwgFile)
                if (ok) {
                    val result = CanonDelivery.tryDeliverPwg(context, pwgFile)
                    Log.i(TAG, "Print-Job delivery result: $result")
                } else {
                    Log.w(TAG, "Failed to convert Print-Job PDF to PWG")
                }
            } else {
                Log.i(TAG, "No PDF detected in Print-Job payload; saved raw for analysis")
                val jobsDir = File(context.filesDir, "jobs")
                if (!jobsDir.exists()) jobsDir.mkdirs()
                val rawFile = File(jobsDir, "ipp-printjob-$requestId.bin")
                rawFile.writeBytes(docBytes)
            }

            return makeIppSuccessResponse(requestId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Print-Job", e)
            return makeIppErrorResponse(requestId, 0x0500)
        }
    }

    private fun makeIppSuccessResponse(requestId: Int): NanoHTTPD.Response {
        val baos = ByteArrayOutputStream()
        baos.write(0x01); baos.write(0x01)
        writeShort(baos, 0x0000)
        writeInt(baos, requestId)
        baos.write(END_OF_ATTRIBUTES_TAG.toInt())
        val resp = baos.toByteArray()
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/ipp",
            ByteArrayInputStream(resp), resp.size.toLong())
    }

    private fun makeIppErrorResponse(requestId: Int, statusCode: Int): NanoHTTPD.Response {
        val baos = ByteArrayOutputStream()
        baos.write(0x01); baos.write(0x01)
        writeShort(baos, statusCode)
        writeInt(baos, requestId)
        baos.write(END_OF_ATTRIBUTES_TAG.toInt())
        val resp = baos.toByteArray()
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/ipp",
            ByteArrayInputStream(resp), resp.size.toLong())
    }

    private fun writeAttribute(baos: ByteArrayOutputStream, valueTag: Byte, name: String, value: String) {
        baos.write(valueTag.toInt())
        val nameBytes = name.toByteArray(StandardCharsets.US_ASCII)
        writeShort(baos, nameBytes.size)
        if (nameBytes.isNotEmpty()) baos.write(nameBytes)
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        writeShort(baos, valueBytes.size)
        if (valueBytes.isNotEmpty()) baos.write(valueBytes)
    }

    private fun writeShort(baos: ByteArrayOutputStream, v: Int) {
        baos.write((v ushr 8) and 0xff)
        baos.write(v and 0xff)
    }

    private fun writeInt(baos: ByteArrayOutputStream, v: Int) {
        baos.write((v ushr 24) and 0xff)
        baos.write((v ushr 16) and 0xff)
        baos.write((v ushr 8) and 0xff)
        baos.write(v and 0xff)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..(data.size - pattern.size)) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
