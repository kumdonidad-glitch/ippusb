/*
tools/ReplayChmpSample.kt

Small Kotlin replay helper to load samples/prn_examples/canon_chmp_sample.bin and either:
 - POST it to an HTTP endpoint (for example a local test server that accepts CHMP payloads), or
 - save a copy to a file (for manual inspection)

Usage (from repo root):
  kotlinc -script tools/ReplayChmpSample.kt -- <options>
Or run with the kotlin runner that comes with the Kotlin distribution.

Options:
  --file <path>       Path to input sample (default: samples/prn_examples/canon_chmp_sample.bin)
  --post <url>        HTTP URL to POST the binary to (optional)
  --out <path>        If set, writes a copy of the sample to this path
  --help              Show this message

This helper intentionally avoids any repo-specific binary dependencies so you can run it on
any JVM with Kotlin scripting support. It uses java.net.HttpURLConnection for simple POSTs.
*/

@file:JvmName("ReplayChmpSample")

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

fun usage() {
    println("Usage: kotlin tools/ReplayChmpSample.kt [--file path] [--post url] [--out path]")
}

fun postBinary(url: String, data: ByteArray) {
    val u = URL(url)
    val conn = u.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/octet-stream")
    conn.setRequestProperty("Content-Length", data.size.toString())
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    conn.outputStream.use { out: OutputStream ->
        out.write(data)
        out.flush()
    }
    val code = conn.responseCode
    println("POST to $url returned HTTP $code ${conn.responseMessage}")
    try {
        val resp = conn.inputStream.bufferedReader().readText()
        println("Response body (truncated to 200 chars):\n${resp.take(200)}")
    } catch (e: Exception) {
        // might be no body or error stream; try errorStream
        val err = conn.errorStream
        if (err != null) {
            val errText = err.bufferedReader().readText()
            println("Error body (truncated to 200 chars):\n${errText.take(200)}")
        }
    }
}

fun main(args: Array<String>) {
    var filePath = "samples/prn_examples/canon_chmp_sample.bin"
    var postUrl: String? = null
    var outPath: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--file" -> { i++; if (i < args.size) filePath = args[i] }
            "--post" -> { i++; if (i < args.size) postUrl = args[i] }
            "--out" -> { i++; if (i < args.size) outPath = args[i] }
            "--help", "-h" -> { usage(); return }
            else -> { println("Unknown arg: ${args[i]}"); usage(); return }
        }
        i++
    }

    val f = File(filePath)
    if (!f.exists()) {
        println("Sample file not found: $filePath")
        return
    }
    val data = f.readBytes()
    println("Read ${data.size} bytes from $filePath")

    if (outPath != null) {
        val outFile = File(outPath)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(data)
        println("Wrote copy to ${outFile.absolutePath}")
    }

    if (postUrl != null) {
        println("Posting sample to $postUrl ...")
        try {
            postBinary(postUrl, data)
        } catch (e: Exception) {
            println("POST failed: ${e.message}")
            e.printStackTrace()
        }
    } else {
        println("No --post URL supplied; use --post to POST the sample to a test endpoint, or --out to write a copy.")
    }
}
