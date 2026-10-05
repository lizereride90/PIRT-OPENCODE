package io.github.zixt233.pirt.runtime.oc

import io.github.zixt233.pirt.model.OcSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlayChatSnapshot(
    val title: String,
    val reply: String?,
    val status: String,
    val canSend: Boolean,
)

/** Projects the sessions owned by the single resident opencode serve host. */
class OcSessionManager(
    private val serve: () -> OcServeClient?,
    private val catalog: OcSessionCatalog,
    private val onActivityChanged: () -> Unit,
    private val onReplyCompleted: (title: String, reply: String?) -> Unit = { _, _ -> },
) {
    private val sessions = LinkedHashMap<String, OcSessionController>()
    private var selected: OcSessionController? = null
    private val _summaries = MutableStateFlow<Map<String, OcSessionSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, OcSessionSummary>> = _summaries.asStateFlow()

    @Synchronized
    fun select(session: OcSession): StateFlow<OcSessionState> {
        val controller = find(session.runtimeKey, session.id) ?: OcSessionController(
            session = session,
            serve = serve,
            catalog = catalog,
            onIdentityChanged = ::controllerIdentityChanged,
            onStateChanged = { controllerChanged() },
            onActivityChanged = onActivityChanged,
        ).also { sessions[session.runtimeKey] = it }
        controller.adopt(session)
        selected = controller
        controller.open()
        publish()
        return controller.state
    }

    fun open(session: OcSession): StateFlow<OcSessionState> = select(session)

    @Synchronized fun state(sessionId: String): StateFlow<OcSessionState>? = controller(sessionId)?.state

    @Synchronized
    fun prompt(sessionId: String, message: String, images: List<OcImage> = emptyList()) {
        require(message.isNotBlank() || images.isNotEmpty()) { "消息不能为空" }
        val controller = checkNotNull(controller(sessionId)) { "会话尚未打开" }
        check(controller === selected) { "只能向当前会话发送消息" }
        controller.prompt(message, images)
    }

    @Synchronized
    fun steer(sessionId: String, message: String, images: List<OcImage> = emptyList()) {
        // OpenCode has no steer primitive; a follow-up message is equivalent.
        prompt(sessionId, message, images)
    }

    @Synchronized
    fun executeCommand(sessionId: String, text: String) {
        val normalized = text.trim()
        require(normalized.startsWith("/") && normalized.length > 1) { "请输入以 / 开头的命令" }
        val controller = checkNotNull(controller(sessionId)) { "会话尚未打开" }
        check(controller === selected) { "只能在当前会话执行命令" }
        controller.executeCommand(normalized)
    }

    @Synchronized fun abort(sessionId: String) = controller(sessionId)?.abort()
    @Synchronized fun requestModels(sessionId: String) = controller(sessionId)?.requestModels()
    @Synchronized fun requestAgents(sessionId: String) = controller(sessionId)?.requestAgents()
    @Synchronized fun requestCommands(sessionId: String) = controller(sessionId)?.requestCommands()
    @Synchronized fun reloadRuntime(sessionId: String) = controller(sessionId)?.reloadRuntime()
    @Synchronized fun requestStats(sessionId: String) = controller(sessionId)?.requestStats()
    @Synchronized fun respondPermission(sessionId: String, requestId: String, allow: Boolean) =
        controller(sessionId)?.respondPermission(requestId, allow)
    @Synchronized fun dismissPermission(sessionId: String, requestId: String) =
        controller(sessionId)?.dismissPermission(requestId)
    @Synchronized fun exportHtml(sessionId: String, callback: (Result<String>) -> Unit) =
        controller(sessionId)?.exportHtml(callback)
            ?: callback(Result.failure(IllegalStateException("会话尚未打开")))
    @Synchronized fun setModel(sessionId: String, provider: String, modelId: String) =
        controller(sessionId)?.setModel(provider, modelId)
    @Synchronized fun setAgent(sessionId: String, agentId: String) =
        controller(sessionId)?.setAgent(agentId)
    @Synchronized fun compact(sessionId: String) = controller(sessionId)?.compact()
    @Synchronized fun setAutoCompaction(sessionId: String, enabled: Boolean) =
        controller(sessionId)?.setAutoCompaction(enabled)
    @Synchronized fun setAutoRetry(sessionId: String, enabled: Boolean) =
        controller(sessionId)?.setAutoRetry(enabled)
    @Synchronized fun fork(sessionId: String, entryId: String, callback: (Result<OcBranchResult?>) -> Unit) =
        controller(sessionId)?.fork(entryId, callback)
            ?: callback(Result.failure(IllegalStateException("会话尚未打开")))
    @Synchronized fun cloneSession(sessionId: String, callback: (Result<OcBranchResult?>) -> Unit) =
        controller(sessionId)?.cloneSession(callback)
            ?: callback(Result.failure(IllegalStateException("会话尚未打开")))

    @Synchronized
    fun rename(session: OcSession, name: String) {
        require(name.isNotBlank()) { "名称不能为空" }
        controller(session.runtimeKey)?.rename(name.trim())
        catalog.rename(session, name.trim())
    }

    @Synchronized
    fun delete(session: OcSession) {
        val key = session.runtimeKey
        sessions.remove(key)?.let { pollStop(it) }
        if (selected?.runtimeKey() == key) selected = null
        catalog.delete(session)
        publish()
    }

    @Synchronized fun activeCount(): Int = if (sessions.isEmpty()) 0 else 1
    @Synchronized fun busyCount(): Int = sessions.values.count(OcSessionController::busy)
    @Synchronized fun hasBusySession(): Boolean = sessions.values.any(OcSessionController::busy)

    fun overlaySnapshot(): OverlayChatSnapshot? {
        val controller = synchronized(this) { selected } ?: return null
        val state = controller.state.value
        val reply = state.messages.lastOrNull { it.role == OcMessageRole.ASSISTANT }?.text
        return OverlayChatSnapshot(
            title = controller.sessionDisplayName(),
            reply = reply,
            status = overlayStatus(state),
            canSend = true,
        )
    }

    fun overlayPrompt(message: String) {
        val controller = synchronized(this) { selected } ?: error("no selected session")
        controller.prompt(message, emptyList())
    }

    fun shutdown() {
        synchronized(this) {
            sessions.values.toList()
        }.forEach { pollStop(it) }
        synchronized(this) {
            sessions.clear()
            selected = null
        }
        publish()
    }

    private fun pollStop(@Suppress("UNUSED_PARAMETER") controller: OcSessionController) {
        controller.abort()
    }

    @Synchronized
    private fun controllerChanged() {
        publish()
        onActivityChanged()
        val selectedController = selected ?: return
        val state = selectedController.state.value
        if (state.turn == TurnState.COMPLETED) {
            val reply = state.messages.lastOrNull { it.role == OcMessageRole.ASSISTANT }?.text
            onReplyCompleted(selectedController.sessionDisplayName(), reply)
        }
    }

    private fun controllerIdentityChanged(controller: OcSessionController, oldKey: String, session: OcSession) {
        synchronized(this) {
            sessions.remove(oldKey)
            sessions[session.runtimeKey] = controller
            publish()
        }
        catalog.refresh()
    }

    private fun publish() {
        synchronized(this) {
            _summaries.value = sessions.mapValues { (_, controller) -> controller.summary() }
        }
        onActivityChanged()
    }

    private fun controller(key: String): OcSessionController? =
        synchronized(this) { sessions[key] ?: sessions.values.firstOrNull { it.matches(key) } }

    private fun find(runtimeKey: String, ocId: String?): OcSessionController? = synchronized(this) {
        sessions[runtimeKey] ?: ocId?.let { id -> sessions.values.firstOrNull { it.matches(id) } }
    }

    private fun overlayStatus(state: OcSessionState): String = when {
        state.turn == TurnState.FAILED -> state.failure?.message ?: "failed"
        state.busy() -> "working"
        else -> "idle"
    }
}

private fun OcSessionState.busy(): Boolean = turn in
    setOf(TurnState.QUEUED, TurnState.GENERATING, TurnState.RUNNING_TOOL, TurnState.COMPACTING, TurnState.STOPPING)
