package com.kumdonidad.ippusb

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(androidx.appcompat.R.layout.abc_action_bar_title_item)

        // Minimal UI: buttons to start/stop service and scan USB devices
        statusText = TextView(this).apply { text = "IPP USB — ready" }
        val startBtn = Button(this).apply { text = "Start Printer Service" }
        val stopBtn = Button(this).apply { text = "Stop Printer Service" }
        val scanBtn = Button(this).apply { text = "Scan USB" }

        startBtn.setOnClickListener {
            val intent = Intent(this, PrinterService::class.java)
            intent.action = PrinterService.ACTION_START
            startForegroundService(intent)
            statusText.text = "Service started"
        }

        stopBtn.setOnClickListener {
            val intent = Intent(this, PrinterService::class.java)
            intent.action = PrinterService.ACTION_STOP
            startForegroundService(intent)
            statusText.text = "Service stopping"
        }

        scanBtn.setOnClickListener {
            scanUsbDevices()
        }

        val layout = androidx.constraintlayout.widget.ConstraintLayout(this)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        layout.addView(scanBtn)
        layout.addView(statusText)
        setContentView(layout)
    }

    private fun scanUsbDevices() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice>? = manager.deviceList
        val sb = StringBuilder()
        if (deviceList.isNullOrEmpty()) {
            sb.append("No USB devices found\n")
        } else {
            for ((_, device) in deviceList) {
                sb.append("Device: ${device.deviceName} vendorId=${device.vendorId} productId=${device.productId}\n")
                // Request permission if needed — real app should prompt and handle the result
            }
        }
        statusText.text = sb.toString()
    }
}
