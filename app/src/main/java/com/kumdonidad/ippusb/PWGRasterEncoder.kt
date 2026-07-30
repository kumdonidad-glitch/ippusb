package com.kumdonidad.ippusb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Minimal PDF -> PWG Raster encoder stub.
 * This encoder renders the first page of a PDF using PdfRenderer and emits a
 * very small PWG-like binary. Real PWG must follow the PWG 5101.1 specification;
 * this is a practical scaffold so the rest of the stack can be tested.
 */
object PWGRasterEncoder {
    private const val TAG = "PWGRasterEncoder"

    fun pdfFirstPageToPwg(context: Context, pdfFile: File, outFile: File): Boolean {
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return false
            val page = renderer.openPage(0)
            val width = page.width
            val height = page.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()

            // Write a tiny PWG-like header (not fully spec-compliant) and raw RGB pixels.
            val fos = FileOutputStream(outFile)
            fos.write("PWG-Raster-Stub\n".toByteArray(Charsets.US_ASCII))
            fos.write("Width: $width\n".toByteArray(Charsets.US_ASCII))
            fos.write("Height: $height\n".toByteArray(Charsets.US_ASCII))
            fos.write("BitsPerPixel: 24\n".toByteArray(Charsets.US_ASCII))
            fos.write("ENDHEADER\n".toByteArray(Charsets.US_ASCII))

            val row = IntArray(width)
            for (y in 0 until height) {
                bmp.getPixels(row, 0, width, 0, y, width, 1)
                val line = ByteArray(width * 3)
                var idx = 0
                for (x in 0 until width) {
                    val px = row[x]
                    line[idx++] = Color.red(px).toByte()
                    line[idx++] = Color.green(px).toByte()
                    line[idx++] = Color.blue(px).toByte()
                }
                fos.write(line)
            }
            fos.flush()
            fos.close()
            Log.i(TAG, "Wrote PWG-like raster to ${outFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF -> PWG", e)
            return false
        }
    }

    fun streamToInputStream(file: File): InputStream = file.inputStream()
}
