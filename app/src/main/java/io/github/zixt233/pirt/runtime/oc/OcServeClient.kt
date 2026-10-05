package io.github.zixt233.pirt.runtime.oc

import io.github.zixt233.pirt.runtime.RuntimeProcessSpec
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner of the single resident `opencode serve` proot process plus a thin
 * authenticated HTTP client for its REST API. One instance lives in
 * [OcAuthManager], which also owns the serve process.
 */
class OcServeClient(
    private val diagnosticTag: String = "oc-serve",
    private val onLog: (message: String, error: Throwable?) -> Unit = { _, _ -> },
    private val processSpec: () -> RuntimeProcessSpec,
    private val serverPassword: () -> String,
) : AutoCloseable {
    @Volatile private var process: Process? = null
    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private var generation = 0L

    val port: Int = 4096

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        ready.set(false)
        val requestGeneration = ++generation
        Thread({
            runCatching { launch(requestGeneration) }
                .onFailure { error ->
                    synchronized(this) {
                        if (requestGeneration == generation) {
                            process?.destroy()
                            process = null
                            running.set(false)
                            onLog(error.message ?: "opencode serve failed", error)
                        }
                    }
                }
        }, "pirt-oc-serve").apply { isDaemon = true }.start()
    }

    fun isReady(): Boolean = ready.get() && process != null

    private fun launch(requestGeneration: Long) {
        val spec = processSpec()
        val child = ProcessBuilder(spec.command).apply {
            environment().putAll(spec.environment)
            redirectErrorStream(true)
        }.start()
        synchronized(this) {
            if (requestGeneration != generation) {
                child.destroy()
                return
            }
            process = child
        }
        Thread({
            runCatching {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it, null) }
                }
            }
        }, "pirt-oc-serve-log").apply { isDaemon = true }.start()

        var healthy = false
        for (attempt in 0 until 120) {
            if (!child.isAlive) break
            healthy = runCatching { get("/global/health").optBoolean("healthy") }.getOrDefault(false)
            if (healthy) break
            Thread.sleep(500)
        }
        synchronized(this) {
            if (requestGeneration != generation || process !== child) {
                child.destroy()
                return
            }
            if (!healthy) {
                child.destroy()
                process = null
                running.set(false)
                error("opencode serve did not become healthy")
            }
            ready.set(true)
        }
        val exit = child.waitFor()
        synchronized(this) {
            if (requestGeneration == generation && process === child) {
                process = null
                running.set(false)
                ready.set(false)
                onLog("opencode serve exited ($exit)", null)
            }
        }
    }

    fun get(path: String): JSONObject = request("GET", path, null)

    fun post(path: String, body: JSONObject? = null): JSONObject = request("POST", path, body)

    fun patch(path: String, body: JSONObject? = null): JSONObject = request("PATCH", path, body)

    fun put(path: String, body: JSONObject? = null): JSONObject = request("PUT", path, body)

    fun delete(path: String): JSONObject = request("DELETE", path, null)

    fun getArray(path: String): JSONArray {
        val text = raw("GET", path, null)
        return runCatching { JSONArray(text) }.getOrElse {
            JSONObject(text).optJSONArray("data") ?: JSONArray()
        }
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val text = raw(method, path, body)
        if (text.isBlank()) return JSONObject()
        return runCatching { JSONObject(text) }.getOrElse {
            JSONObject().put("data", text)
        }
    }

    private fun raw(method: String, path: String, body: JSONObject?): String {
        check(running.get()) { "opencode serve is not running" }
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json")
            val credentials = "opencode:${serverPassword()}"
            val encoded = android.util.Base64.encodeToString(credentials.toByteArray(), android.util.Base64.NO_WRAP)
            setRequestProperty("Authorization", "Basic $encoded")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("opencode $method $path -> HTTP $code: ${text.take(300)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    override fun close() {
        generation++
        process?.destroy()
        process = null
        running.set(false)
        ready.set(false)
    }

    companion object {
        fun randomPassword(length: Int = 24): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            val random = SecureRandom()
            return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
    }
}
