package com.kumdonidad.ippusb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PrinterService : Service() {

    companion object {
        const val ACTION_START = "com.kumdonidad.ippusb.action.START"
        const val ACTION_STOP = "com.kumdonidad.ippusb.action.STOP"
        const val TAG = "PrinterService"
    }

    private var ippServer: HttpIppServer? = null
    private var mdnsRegistrar: JmDNSRegistrar? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundServiceWork()
            ACTION_STOP -> stopForegroundServiceWork()
            else -> startForegroundServiceWork()
        }
        return START_STICKY
    }

    private fun startForegroundServiceWork() {
        createNotificationChannel()
        val notif = Notification.Builder(this, "ippusb-channel")
            .setContentTitle("IPP USB Printer Service")
            .setContentText("Advertising printers and accepting jobs")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ippServer = HttpIppServer(this@PrinterService, 6310)
                ippServer?.start()

                mdnsRegistrar = JmDNSRegistrar(this@PrinterService).apply {
                    registerIppPrinter("Canon G2730", 6310, "/ipp/printer1")
                }

                Log.i(TAG, "IPP server and mDNS started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start servers", e)
            }
        }
    }

    private fun stopForegroundServiceWork() {
        ippServer?.stop()
        ippServer = null
        mdnsRegistrar?.unregisterAll()
        mdnsRegistrar = null
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel("ippusb-channel", "IPPUSB", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopForegroundServiceWork()
        super.onDestroy()
    }
}
