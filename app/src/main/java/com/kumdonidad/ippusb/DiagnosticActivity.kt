package com.kumdonidad.ippusb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DiagnosticActivity : AppCompatActivity() {
    companion object { private const val TAG = "DiagnosticActivity"; private const val ACTION_USB_PERMISSION = "com.kumdonidad.ippusb.USB_PERMISSION" }

    private lateinit var statusView: TextView
    private lateinit var scroll: ScrollView
    private var pendingDeviceToTest: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            appendStatus("Permission granted for ${device.deviceName}")
                            runGetCapability(device)
                        } else {
                            appendStatus("Permission denied for device")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendStatus("USB device attached")
                    listDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendStatus("USB device detached")
                    listDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { text = "Diagnostic\n" }
        val scanBtn = Button(this).apply { text = "Scan USB Devices" }
        val capBtn = Button(this).apply { text = "GetCapability on selected" }
        val sendBtn = Button(this).apply { text = "Send test PWG job" }
        scroll = ScrollView(this)
        scroll.addView(statusView)
        val layout = androidx.constraintlayout.widget.ConstraintLayout(this)
        layout.addView(scanBtn)
        layout.addView(capBtn)
        layout.addView(sendBtn)
        layout.addView(scroll)
        setContentView(layout)

        scanBtn.setOnClickListener { listDevices() }
        capBtn.setOnClickListener {
            val device = pendingDeviceToTest
            if (device == null) appendStatus("No device selected") else requestPermissionAndRun(device)
        }
        sendBtn.setOnClickListener {
            val device = pendingDeviceToTest
            if (device == null) appendStatus("No device selected") else requestPermissionAndSend(device)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)
        appendStatus("Diagnostic ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun appendStatus(s: String) {
        runOnUiThread {
            statusView.append("\n" + s)
        }
        Log.i(TAG, s)
    }

    private fun listDevices() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList
        appendStatus("Found ${devices.size} USB devices")
        var i = 0
        for ((_, d) in devices) {
            appendStatus("[$i] ${d.deviceName} vendor=${d.vendorId} product=${d.productId}")
            i++
            // Choose the first printer-class device as the default to test
            if (pendingDeviceToTest == null) pendingDeviceToTest = d
        }
        if (devices.isEmpty()) appendStatus("(none)")
    }

    private fun requestPermissionAndRun(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            runGetCapability(device)
            return
        }
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
        manager.requestPermission(device, pi)
    }

    private fun runGetCapability(device: UsbDevice) {
        appendStatus("Running GetCapability on ${device.deviceName}")
        CoroutineScope(Dispatchers.IO).launch {
            val client = CanonChmpClient(this@DiagnosticActivity)
            val res = client.getCapability(device)
            appendStatus("GetCapability ok=${res.ok} body=\n${res.body}")
        }
    }

    private fun requestPermissionAndSend(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            sendTestJob(device)
            return
        }
        val pi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
        manager.requestPermission(device, pi)
    }

    private fun sendTestJob(device: UsbDevice) {
        appendStatus("Sending test PWG job to ${device.deviceName}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val samplePdf = File(filesDir, "sample.pdf")
                if (!samplePdf.exists()) {
                    // create a tiny one-page PDF using PdfDocument
                    val doc = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(612, 792, 1).create() // US Letter points
                    val page = doc.startPage(pageInfo)
                    val canvas: Canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint().apply {
                        color = Color.BLACK
                        textSize = 24f
                    }
                    canvas.drawText("PWG Test Page", 72f, 72f, paint)
                    doc.finishPage(page)
                    doc.writeTo(samplePdf.outputStream())
                    doc.close()
                }

                val pwgOut = File(filesDir, "sample.pwg")
                val ok = PWGRasterEncoder.pdfToPwgAllPages(this@DiagnosticActivity, samplePdf, pwgOut)
                if (!ok) {
                    appendStatus("Failed to encode PDF -> PWG")
                    return@launch
                }
                val client = CanonChmpClient(this@DiagnosticActivity)
                val fis = pwgOut.inputStream()
                val res = client.postDocument(device, fis, "application/octet-stream")
                fis.close()
                appendStatus("postDocument ok=${res.ok} body=\n${res.body}")
            } catch (e: Exception) {
                appendStatus("Error sending test job: ${e.message}")
            }
        }
    }
}
