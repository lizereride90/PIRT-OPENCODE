package io.github.zixt233.pirt.runtime.oc

import android.content.Context
import io.github.zixt233.pirt.model.OcSession
import io.github.zixt233.pirt.runtime.RuntimeDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/** Projection of the opencode serve session list. */
class OcSessionCatalog(
    context: Context,
    private val serve: () -> OcServeClient?,
) {
    private val appContext = context.applicationContext
    private val loading = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val _sessions = MutableStateFlow<List<OcSession>>(emptyList())
    val sessions: StateFlow<List<OcSession>> = _sessions.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun refresh() {
        refreshRequested.set(true)
        if (!loading.compareAndSet(false, true)) return
        Thread({
            var attempts = 0
            do {
                refreshRequested.set(false)
                runCatching { list() }
                    .onSuccess {
                        _sessions.value = it.sortedByDescending(OcSession::updatedAt)
                        _loaded.value = true
                    }
                    .onFailure { RuntimeDiagnostics.error(appContext, "oc-catalog", "读取会话失败", it) }
                attempts++
                // Serve boots asynchronously; retry a few times on cold start.
                if (!_loaded.value && attempts < 10) {
                    Thread.sleep(2000)
                    refreshRequested.set(true)
                }
            } while (refreshRequested.get())
            loading.set(false)
            if (refreshRequested.get()) refresh()
        }, "pirt-oc-catalog").apply { isDaemon = true }.start()
    }

    fun rename(session: OcSession, name: String) {
        val id = session.id ?: return
        mutate("rename", id, name.trim())
    }

    fun delete(session: OcSession) {
        val id = session.id ?: return
        mutate("delete", id)
    }

    private fun mutate(command: String, vararg args: String) {
        Thread({
            runCatching {
                val client = serve() ?: error("serve is not running")
                when (command) {
                    "rename" -> client.patch("/session/${args[0]}", org.json.JSONObject().put("title", args[1]))
                    "delete" -> client.delete("/session/${args[0]}")
                    else -> error("Unknown catalog command: $command")
                }
                refresh()
            }.onFailure { RuntimeDiagnostics.error(appContext, "oc-catalog", "$command 会话失败", it) }
        }, "pirt-oc-$command").apply { isDaemon = true }.start()
    }

    private fun list(): List<OcSession> {
        val client = serve() ?: error("serve is not running")
        return decode(client.getArray("/session"))
    }

    private fun decode(values: JSONArray): List<OcSession> = buildList {
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            val id = value.optString("id").ifBlank { continue }
            val time = value.optJSONObject("time")
            add(OcSession(
                runtimeKey = "oc:$id",
                id = id,
                name = value.optString("title"),
                firstMessage = null,
                createdAt = time?.optLong("created") ?: 0,
                updatedAt = time?.optLong("updated") ?: 0,
                messageCount = 0,
            ))
        }
    }
}
