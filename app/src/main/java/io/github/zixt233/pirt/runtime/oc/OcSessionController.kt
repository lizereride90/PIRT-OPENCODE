package io.github.zixt233.pirt.runtime.oc

import io.github.zixt233.pirt.model.OcSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val POLL_INTERVAL_MS = 600L
private const val POLL_TIMEOUT_MS = 30 * 60 * 1000L

/** Per-conversation projection over one opencode serve session. */
class OcSessionController(
    private var session: OcSession,
    private val serve: () -> OcServeClient?,
    private val catalog: OcSessionCatalog,
    private val onIdentityChanged: (controller: OcSessionController, oldKey: String, session: OcSession) -> Unit = { _, _, _ -> },
    private val onStateChanged: () -> Unit = {},
    private val onActivityChanged: () -> Unit = {},
    private val diagnostic: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(
        OcSessionState(
            process = ProcessState.STARTING,
            historyLoaded = session.id == null,
            agent = OcAgentRuntimeState(sessionId = session.id),
        ),
    )
    val state: StateFlow<OcSessionState> = _state.asStateFlow()
    private var serverId: String? = session.id
    private var selectedProvider: String = ""
    private var selectedModelId: String = ""
    private var selectedModelName: String = ""
    private var selectedAgentId: String = ""
    @Volatile private var pollGeneration = 0L
    @Volatile private var openGeneration = 0L

    fun adopt(value: OcSession) {
        session = value
        serverId = value.id ?: serverId
    }

    fun open() {
        val generation = ++openGeneration
        val client = serve()
        if (client == null || !client.isReady()) {
            update { it.copy(process = ProcessState.STARTING) }
            // Serve boots asynchronously; retry shortly instead of sticking.
            Thread({
                Thread.sleep(1500)
                if (generation == openGeneration) open()
            }, "pirt-oc-open-retry").apply { isDaemon = true }.start()
            return
        }
        openGeneration++
        update {
            it.copy(
                process = if (client.isReady()) ProcessState.RUNNING else ProcessState.STARTING,
                agentLoaded = true,
            )
        }
        if (serverId != null) refresh()
        else {
            update { it.copy(historyLoaded = true, agentLoaded = true) }
            requestModels()
            requestCommands()
            requestAgents()
        }
    }

    fun prompt(message: String, images: List<OcImage>) {
        val client = serve() ?: return fail("opencode serve is not running")
        update { it.copy(turn = TurnState.QUEUED, failure = null) }
        Thread({
            runCatching {
                val id = ensureServer(client)
                val parts = JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", message))
                    images.forEach { image ->
                        put(JSONObject().put("type", "file").put("mime", image.mimeType)
                            .put("url", "data:${image.mimeType};base64,${image.data}"))
                    }
                }
                val body = JSONObject().put("parts", parts)
                modelObject()?.let { body.put("model", it) }
                if (selectedAgentId.isNotBlank()) body.put("agent", selectedAgentId)
                try {
                    client.post("/session/$id/prompt_async", body)
                } catch (first: Exception) {
                    // Fall back to text-only without explicit model/agent selection.
                    val retry = JSONObject().put("parts", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", message))
                    })
                    client.post("/session/$id/prompt_async", retry)
                }
                update { it.copy(turn = TurnState.GENERATING) }
                pollUntilIdle(client, id)
            }.onFailure { error -> fail(error.message ?: "prompt failed") }
        }, "pirt-oc-prompt").apply { isDaemon = true }.start()
    }

    fun executeCommand(text: String) {
        val normalized = text.trim()
        require(normalized.startsWith("/") && normalized.length > 1) { "请输入以 / 开头的命令" }
        val client = serve() ?: return fail("opencode serve is not running")
        update { it.copy(turn = TurnState.QUEUED, failure = null) }
        Thread({
            runCatching {
                val id = ensureServer(client)
                val bare = normalized.removePrefix("/").trim()
                val space = bare.indexOf(' ')
                val command = if (space < 0) bare else bare.substring(0, space)
                val arguments = if (space < 0) "" else bare.substring(space + 1)
                val body = JSONObject().put("command", command).put("arguments", arguments)
                modelObject()?.let { body.put("model", it) }
                if (selectedAgentId.isNotBlank()) body.put("agent", selectedAgentId)
                // prompt_async waits for the full turn; the poll loop streams progress.
                client.post("/session/$id/prompt_async", JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", normalized))
                }))
                update { it.copy(turn = TurnState.GENERATING) }
                pollUntilIdle(client, id)
            }.onFailure { error -> fail(error.message ?: "command failed") }
        }, "pirt-oc-command").apply { isDaemon = true }.start()
    }

    fun abort() {
        pollGeneration++
        val client = serve() ?: return
        val id = serverId ?: return
        runCatching { client.post("/session/$id/abort") }
        update { it.copy(turn = TurnState.IDLE) }
        onActivityChanged()
    }

    fun requestModels() {
        val client = serve() ?: return
        Thread({
            runCatching {
                val providers = client.get("/config/providers")
                val models = buildList {
                    val list = providers.optJSONArray("providers") ?: JSONArray()
                    for (i in 0 until list.length()) {
                        val provider = list.optJSONObject(i) ?: continue
                        val providerId = provider.optString("id")
                        val defs = provider.optJSONObject("models") ?: continue
                        defs.keys().forEach { modelId ->
                            val def = defs.optJSONObject(modelId)
                            add(OcModel(
                                provider = providerId,
                                id = modelId,
                                name = def?.optString("name").orEmpty().ifBlank { modelId },
                            ))
                        }
                    }
                }
                update { it.copy(models = models, modelsRevision = it.modelsRevision + 1) }
            }.onFailure { error -> diagnostic("models failed: ${error.message}", error) }
        }, "pirt-oc-models").apply { isDaemon = true }.start()
    }

    fun requestAgents() {
        val client = serve() ?: return
        Thread({
            runCatching {
                val agents = client.getArray("/agent").let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            val agent = array.optJSONObject(i) ?: continue
                            val id = agent.optString("name").ifBlank { agent.optString("id") }
                            if (id.isBlank()) continue
                            add(OcAgent(id = id, name = id, description = agent.optString("description")))
                        }
                    }
                }
                update { it.copy(agents = agents, agentsRevision = it.agentsRevision + 1) }
            }.onFailure { error -> diagnostic("agents failed: ${error.message}", error) }
        }, "pirt-oc-agents").apply { isDaemon = true }.start()
    }

    fun requestCommands() {
        val client = serve() ?: return
        Thread({
            runCatching {
                val commands = client.getArray("/command").let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            val command = array.optJSONObject(i) ?: continue
                            val name = command.optString("name").ifBlank { continue }
                            add(OcCommand(
                                name = name,
                                description = command.optString("description"),
                                source = "opencode",
                            ))
                        }
                    }
                }
                update { it.copy(commands = commands, commandsRevision = it.commandsRevision + 1) }
            }.onFailure { error -> diagnostic("commands failed: ${error.message}", error) }
        }, "pirt-oc-commands").apply { isDaemon = true }.start()
    }

    fun reloadRuntime() {
        requestModels()
        requestCommands()
        requestAgents()
        refresh()
        val client = serve() ?: return
        Thread({
            runCatching {
                val statuses = client.get("/mcp")
                val map = buildMap<String, String> {
                    statuses.keys().forEach { name ->
                        put(name, statuses.optJSONObject(name)?.optString("status", "unknown") ?: "unknown")
                    }
                }
                update { it.copy(extensionStatuses = map) }
            }.onFailure { error -> diagnostic("mcp status failed: ${error.message}", error) }
        }, "pirt-oc-mcp").apply { isDaemon = true }.start()
    }

    fun requestStats() = refresh()

    fun respondPermission(requestId: String, allow: Boolean) {
        val client = serve() ?: return
        val id = serverId ?: return
        Thread({
            runCatching {
                client.post("/session/$id/permissions/$requestId",
                    JSONObject().put("response", if (allow) "allow" else "reject"))
                update { state ->
                    state.copy(extensionUiRequests = state.extensionUiRequests.filterNot { it.id == requestId })
                }
            }.onFailure { error -> fail(error.message ?: "permission response failed") }
        }, "pirt-oc-permission").apply { isDaemon = true }.start()
    }

    fun dismissPermission(requestId: String) =
        update { it.copy(extensionUiRequests = it.extensionUiRequests.filterNot { req -> req.id == requestId }) }

    fun setModel(provider: String, modelId: String) {
        val client = serve()
        selectedProvider = provider
        selectedModelId = modelId
        selectedModelName = client?.let {
            runCatching {
                update { state ->
                    val match = state.models.firstOrNull { it.provider == provider && it.id == modelId }
                    state.copy(agent = state.agent.copy(
                        provider = provider,
                        modelId = modelId,
                        modelName = match?.name ?: modelId,
                    ))
                }
            }.isSuccess
            state.value.agent.modelName
        } ?: modelId
        update { it.copy(agent = it.agent.copy(provider = provider, modelId = modelId, modelName = selectedModelName)) }
        if (client != null) {
            Thread({
                runCatching {
                    client.patch("/config", JSONObject().put("model", "$provider/$modelId"))
                }.onFailure { error -> diagnostic("default model not saved: ${error.message}", error) }
            }, "pirt-oc-model").apply { isDaemon = true }.start()
        }
    }

    fun setAgent(agentId: String) {
        selectedAgentId = agentId
        update { it.copy(agent = it.agent.copy(agentId = agentId)) }
    }

    fun compact() {
        val client = serve() ?: return
        val id = serverId ?: return
        update { it.copy(turn = TurnState.COMPACTING, failure = null) }
        Thread({
            runCatching {
                val body = JSONObject()
                if (selectedProvider.isNotBlank() && selectedModelId.isNotBlank()) {
                    body.put("providerID", selectedProvider).put("modelID", selectedModelId)
                }
                client.post("/session/$id/summarize", body)
                refresh()
                update { it.copy(turn = TurnState.IDLE) }
            }.onFailure { error -> fail(error.message ?: "summarize failed") }
        }, "pirt-oc-compact").apply { isDaemon = true }.start()
    }

    fun setAutoCompaction(enabled: Boolean) {
        // No verified server toggle; keep the UI switch as a local preference echo.
        update { it.copy(agent = it.agent.copy(autoCompactionEnabled = enabled)) }
    }

    fun setAutoRetry(enabled: Boolean) {
        // No verified server toggle; kept for UI compatibility.
        diagnostic("auto-retry=$enabled (client-side only)", null)
    }

    fun rename(name: String) {
        val client = serve() ?: return
        val id = serverId ?: return
        Thread({
            runCatching {
                client.patch("/session/$id", JSONObject().put("title", name))
                catalog.refresh()
            }.onFailure { error -> fail(error.message ?: "rename failed") }
        }, "pirt-oc-rename").apply { isDaemon = true }.start()
    }

    fun fork(entryId: String, callback: (Result<OcBranchResult?>) -> Unit) {
        branch(entryId, callback)
    }

    fun cloneSession(callback: (Result<OcBranchResult?>) -> Unit) {
        branch(null, callback)
    }

    private fun branch(entryId: String?, callback: (Result<OcBranchResult?>) -> Unit) {
        val client = serve() ?: return callback(Result.failure(IllegalStateException("serve is not running")))
        val id = serverId ?: return callback(Result.failure(IllegalStateException("no server session yet")))
        Thread({
            runCatching {
                val body = JSONObject()
                if (entryId != null) body.put("messageID", entryId)
                val session = client.post("/session/$id/fork", body)
                val newId = session.optString("id").ifBlank {
                    error("fork did not return a session id")
                }
                catalog.refresh()
                callback(Result.success(OcBranchResult("oc:$newId")))
            }.onFailure { callback(Result.failure(it)) }
        }, "pirt-oc-fork").apply { isDaemon = true }.start()
    }

    fun exportHtml(callback: (Result<String>) -> Unit) {
        runCatching {
            val state = _state.value
            buildString {
                appendLine("<!doctype html><html><head><meta charset=\"utf-8\">")
                appendLine("<title>${escapeHtml(session.displayName)}</title></head><body>")
                state.messages.forEach { message ->
                    appendLine("<h3>${message.role}</h3>")
                    appendLine("<pre>${escapeHtml(message.text)}</pre>")
                }
                appendLine("</body></html>")
            }
        }.let(callback)
    }

    fun busy(): Boolean = state.value.turn in
        setOf(TurnState.QUEUED, TurnState.GENERATING, TurnState.RUNNING_TOOL, TurnState.COMPACTING, TurnState.STOPPING)

    fun summary() = OcSessionSummary(state.value.process, state.value.turn)

    fun sessionDisplayName(): String = session.displayName

    fun runtimeKey(): String = session.runtimeKey

    fun matches(key: String): Boolean =
        session.runtimeKey == key || session.id == key || serverId == key || "oc:$serverId" == key

    fun refresh() {
        val client = serve() ?: return
        val id = serverId ?: return
        Thread({
            runCatching {
                update { it.copy(process = if (client.isReady()) ProcessState.RUNNING else ProcessState.STARTING) }
                applyMessages(client.getArray("/session/$id/message"))
                applyStatus(client.get("/session/status"))
                update { it.copy(historyLoaded = true, agentLoaded = true) }
            }.onFailure { error -> diagnostic("refresh failed: ${error.message}", error) }
        }, "pirt-oc-refresh").apply { isDaemon = true }.start()
    }

    private fun ensureServer(client: OcServeClient): String {
        serverId?.let { return it }
        val created = client.post("/session", JSONObject().put("title", session.displayName.take(60)))
        val id = created.optString("id").ifBlank { error("opencode did not return a session id") }
        val oldKey = session.runtimeKey
        serverId = id
        val adopted = session.copy(id = id, runtimeKey = "oc:$id")
        session = adopted
        update { it.copy(agent = it.agent.copy(sessionId = id), historyLoaded = true, agentLoaded = true) }
        onIdentityChanged(this, oldKey, adopted)
        catalog.refresh()
        return id
    }

    private fun pollUntilIdle(client: OcServeClient, id: String) {
        val generation = ++pollGeneration
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var sawAssistant = false
        while (generation == pollGeneration && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            if (generation != pollGeneration) return
            runCatching {
                applyMessages(client.getArray("/session/$id/message"))
                val status = client.get("/session/status")
                val active = applyStatus(status)
                if (active) {
                    if (!sawAssistant && _state.value.messages.any { it.role == OcMessageRole.ASSISTANT }) {
                        sawAssistant = true
                    }
                    update { state ->
                        val hasOpenTools = state.execution.any { item -> item is OcToolState && !item.finished }
                        state.copy(turn = if (hasOpenTools) TurnState.RUNNING_TOOL else TurnState.GENERATING)
                    }
                } else {
                    finishExecution()
                    update { it.copy(turn = TurnState.COMPLETED) }
                    onActivityChanged()
                    return
                }
            }.onFailure { error ->
                diagnostic("poll failed: ${error.message}", error)
            }
        }
        if (generation == pollGeneration) {
            finishExecution()
            update { it.copy(turn = TurnState.COMPLETED) }
        }
    }

    private fun applyMessages(array: JSONArray) {
        val messages = buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val info = entry.optJSONObject("info") ?: continue
                val role = when (info.optString("role")) {
                    "assistant" -> OcMessageRole.ASSISTANT
                    "system" -> OcMessageRole.SYSTEM
                    else -> OcMessageRole.USER
                }
                val parts = entry.optJSONArray("parts") ?: JSONArray()
                val (text, _) = decodeOcParts(parts)
                if (role == OcMessageRole.SYSTEM && text.isBlank()) continue
                add(OcMessage(
                    id = info.optString("id").ifBlank { "m$i" },
                    role = role,
                    text = text,
                    entryId = info.optString("id").ifBlank { null },
                ))
            }
        }
        val execution = buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val parts = entry.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    val id = part.optString("id").ifBlank { "p$i-$j" }
                    when (part.optString("type")) {
                        "reasoning" -> add(OcThinkingState(
                            id = id,
                            text = part.optString("text"),
                            finished = false,
                        ))
                        "tool" -> {
                            val state = part.optJSONObject("state") ?: JSONObject()
                            val status = state.optString("status")
                            add(OcToolState(
                                id = id,
                                name = part.optString("tool"),
                                summary = state.optString("title", part.optString("tool")),
                                input = state.opt("input")?.toString().orEmpty(),
                                output = state.optString("output"),
                                finished = status in setOf("completed", "error"),
                                failed = status == "error",
                            ))
                        }
                    }
                }
            }
        }
        var inputChars = 0L
        var outputChars = 0L
        messages.forEach {
            if (it.role == OcMessageRole.USER) inputChars += it.text.length
            else outputChars += it.text.length
        }
        update {
            it.copy(
                messages = messages,
                execution = execution,
                stats = OcSessionStats(tokens = OcTokenUsage(
                    input = inputChars / 4,
                    output = outputChars / 4,
                    total = (inputChars + outputChars) / 4,
                )),
            )
        }
        onStateChanged()
    }

    /** Returns true while the session is still working. Surfaces permission prompts. */
    private fun applyStatus(status: JSONObject): Boolean {
        val text = status.toString()
        scanPermissions(status)
        val lowered = text.lowercase()
        if ("busy" in lowered || "working" in lowered || "running" in lowered) return true
        // Per-session object form: {"<id>": {"type": ...}} — idle when absent or explicit.
        serverId?.let { id ->
            val scoped = status.optJSONObject(id) ?: return text.contains("\"$id\"")
            val type = scoped.optString("type").lowercase()
            if (type.isNotBlank()) return type !in setOf("idle", "completed", "done")
        }
        return false
    }

    private fun scanPermissions(node: Any?) {
        val found = mutableListOf<OcPermissionRequest>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    var looksPermission = false
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.equals("permission", ignoreCase = true) ||
                            key.equals("permissionRequest", ignoreCase = true)
                        ) looksPermission = true
                    }
                    if (looksPermission) {
                        val inner = value.optJSONObject("permission") ?: value.optJSONObject("permissionRequest") ?: value
                        val id = inner.optString("id").ifBlank { inner.optString("permissionID") }
                        if (id.isNotBlank()) {
                            found += OcPermissionRequest(
                                id = id,
                                method = "permission",
                                title = inner.optString("title", "Permission requested"),
                                message = inner.optString("message", inner.optString("question")),
                                options = listOf("Allow", "Deny"),
                            )
                        }
                    }
                    val iter = value.keys()
                    while (iter.hasNext()) visit(value.opt(iter.next()))
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) visit(value.opt(i))
                }
            }
        }
        visit(node)
        if (found.isNotEmpty()) {
            update { state ->
                val known = state.extensionUiRequests.map { it.id }.toSet()
                state.copy(extensionUiRequests = state.extensionUiRequests + found.filterNot { it.id in known })
            }
        }
    }

    private fun finishExecution() {
        update { state ->
            state.copy(execution = state.execution.map { item ->
                when (item) {
                    is OcThinkingState -> item.copy(finished = true)
                    is OcToolState -> item.copy(finished = true)
                }
            })
        }
    }

    private fun modelObject(): JSONObject? {
        if (selectedProvider.isBlank() || selectedModelId.isBlank()) return null
        return JSONObject().put("providerID", selectedProvider).put("modelID", selectedModelId)
    }

    private fun fail(message: String) {
        pollGeneration++
        update { it.copy(turn = TurnState.FAILED, failure = OcFailure.Process(message)) }
        onActivityChanged()
        diagnostic(message, null)
    }

    private fun update(transform: (OcSessionState) -> OcSessionState) {
        _state.value = transform(_state.value)
        onStateChanged()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
