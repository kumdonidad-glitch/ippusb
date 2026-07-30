package com.kumdonidad.ippusb

import android.content.Context
import android.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

class JmDNSRegistrar(private val context: Context) {
    private var jmdns: JmDNS? = null

    fun registerIppPrinter(friendlyName: String, port: Int, resourcePath: String) {
        Thread {
            try {
                val addr = InetAddress.getByName("0.0.0.0")
                jmdns = JmDNS.create(addr)
                val txt = HashMap<String, String>()
                txt["txtvers"] = "1"
                txt["qtotal"] = "1"
                txt["ty"] = friendlyName
                txt["product"] = "(Canon G2730)"
                txt["rp"] = resourcePath
                txt["note"] = "Android USB"
                txt["pdl"] = "application/pdf,image/urf"
                txt["URF"] = "none"

                val info = ServiceInfo.create("_ipp._tcp.local.", friendlyName, port, 0, 0, txt)
                jmdns?.registerService(info)
                Log.i("JmDNSRegistrar", "Registered mDNS service for $friendlyName")
            } catch (e: Exception) {
                Log.e("JmDNSRegistrar", "Failed to register mDNS", e)
            }
        }.start()
    }

    fun unregisterAll() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (_: Exception) {}
    }
}
