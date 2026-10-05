package io.github.zixt233.pirt.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zixt233.pirt.model.ChatImage
import io.github.zixt233.pirt.model.ChatMessage
import io.github.zixt233.pirt.model.MessageRole
import io.github.zixt233.pirt.model.OcSession
import io.github.zixt233.pirt.runtime.RuntimeConnection
import io.github.zixt233.pirt.runtime.oc.OcAgent
import io.github.zixt233.pirt.runtime.oc.OcBranchResult
import io.github.zixt233.pirt.runtime.oc.OcCommand
import io.github.zixt233.pirt.runtime.oc.OcExecutionItem
import io.github.zixt233.pirt.runtime.oc.OcImage
import io.github.zixt233.pirt.runtime.oc.OcMessageRole
import io.github.zixt233.pirt.runtime.oc.OcModel
import io.github.zixt233.pirt.runtime.oc.OcPermissionRequest
import io.github.zixt233.pirt.runtime.oc.OcSessionManager
import io.github.zixt233.pirt.runtime.oc.OcSessionState
import io.github.zixt233.pirt.runtime.oc.OcSessionStats
import io.github.zixt233.pirt.runtime.oc.ProcessState
import io.github.zixt233.pirt.runtime.oc.TurnState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ChatUiState(
    val process: ProcessState = ProcessState.STARTING,
    val turn: TurnState = TurnState.IDLE,
    val historyLoaded: Boolean = false,
    val agentLoaded: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val execution: List<OcExecutionItem> = emptyList(),
    val provider: String = "",
    val modelId: String = "",
    val modelName: String = "",
    val agentId: String = "",
    val thinkingLevel: String? = null,
    val sessionId: String? = null,
    val streaming: Boolean = false,
    val steeringMessages: List<String> = emptyList(),
    val models: List<OcModel> = emptyList(),
    val modelsRevision: Long = 0,
    val agents: List<OcAgent> = emptyList(),
    val agentsRevision: Long = 0,
    val commands: List<OcCommand> = emptyList(),
    val commandsRevision: Long = 0,
    val extensionUiRequests: List<OcPermissionRequest> = emptyList(),
    val extensionStatuses: Map<String, String> = emptyMap(),
    val extensionWidgets: Map<String, List<String>> = emptyMap(),
    val thinkingLevels: List<String> = emptyList(),
    val thinkingLevelsRevision: Long = 0,
    val stats: OcSessionStats? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = turn in setOf(TurnState.QUEUED, TurnState.GENERATING, TurnState.RUNNING_TOOL, TurnState.COMPACTING, TurnState.STOPPING)
    val ready: Boolean get() = process == ProcessState.RUNNING && historyLoaded && agentLoaded
}

class ChatViewModel(
    private val session: OcSession,
    private val runtime: RuntimeConnection,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState(historyLoaded = session.id == null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch {
            runtime.manager.collectLatest { manager ->
                sessionJob?.cancel()
                if (manager == null) return@collectLatest
                bind(manager)
            }
        }
    }

    /** Rebinds a restored screen to a live controller, recreating a process stopped while off-screen. */
    fun activate() {
        runtime.manager.value?.let(::bind)
    }

    fun prompt(message: String, images: List<ChatImage> = emptyList()) {
        runtime.manager.value?.prompt(session.runtimeKey, message, images.map { OcImage(it.data, it.mimeType) })
    }

    fun steer(message: String, images: List<ChatImage> = emptyList()) {
        runtime.manager.value?.steer(session.runtimeKey, message, images.map { OcImage(it.data, it.mimeType) })
    }

    fun executeCommand(text: String) = runtime.manager.value?.executeCommand(session.runtimeKey, text)

    fun abort() = runtime.manager.value?.abort(session.runtimeKey)
    fun requestModels() = runtime.manager.value?.requestModels(session.runtimeKey)
    fun requestAgents() = runtime.manager.value?.requestAgents(session.runtimeKey)
    fun requestCommands() = runtime.manager.value?.requestCommands(session.runtimeKey)
    fun reloadRuntime() = runtime.manager.value?.reloadRuntime(session.runtimeKey)
    fun requestStats() = runtime.manager.value?.requestStats(session.runtimeKey)
    fun respondPermission(
        requestId: String,
        allow: Boolean,
    ) = runtime.manager.value?.respondPermission(session.runtimeKey, requestId, allow)
    fun dismissPermission(requestId: String) = runtime.manager.value?.dismissPermission(session.runtimeKey, requestId)
    fun exportHtml(callback: (Result<String>) -> Unit) {
        val manager = runtime.manager.value
            ?: return callback(Result.failure(IllegalStateException("PIRT 尚未连接")))
        runCatching {
            manager.exportHtml(session.runtimeKey) { result -> viewModelScope.launch { callback(result) } }
        }.onFailure { callback(Result.failure(it)) }
    }
    fun setModel(provider: String, modelId: String) = runtime.manager.value?.setModel(session.runtimeKey, provider, modelId)
    fun setAgent(agentId: String) = runtime.manager.value?.setAgent(session.runtimeKey, agentId)
    fun compact() = runtime.manager.value?.compact(session.runtimeKey)
    fun setAutoCompaction(enabled: Boolean) = runtime.manager.value?.setAutoCompaction(session.runtimeKey, enabled)
    fun setAutoRetry(enabled: Boolean) = runtime.manager.value?.setAutoRetry(session.runtimeKey, enabled)
    fun fork(entryId: String, callback: (Result<OcBranchResult?>) -> Unit) {
        val manager = runtime.manager.value ?: return callback(Result.failure(IllegalStateException("PIRT 尚未连接")))
        runCatching { manager.fork(session.runtimeKey, entryId) { result ->
            viewModelScope.launch { callback(result) }
        } }.onFailure { callback(Result.failure(it)) }
    }

    fun cloneSession(callback: (Result<OcBranchResult?>) -> Unit) {
        val manager = runtime.manager.value ?: return callback(Result.failure(IllegalStateException("PIRT 尚未连接")))
        runCatching { manager.cloneSession(session.runtimeKey) { result ->
            viewModelScope.launch { callback(result) }
        } }.onFailure { callback(Result.failure(it)) }
    }

    private fun bind(manager: OcSessionManager) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            manager.open(session).collect(::accept)
        }
    }

    private fun accept(value: OcSessionState) {
        _state.value = ChatUiState(
            process = value.process,
            turn = value.turn,
            historyLoaded = value.historyLoaded,
            agentLoaded = value.agentLoaded,
            messages = value.messages.map { message ->
                ChatMessage(
                    id = message.id,
                    role = when (message.role) {
                        OcMessageRole.USER -> MessageRole.USER
                        OcMessageRole.ASSISTANT -> MessageRole.ASSISTANT
                        OcMessageRole.SYSTEM -> MessageRole.SYSTEM
                    },
                    text = message.text,
                    images = message.images.map { ChatImage(it.data, it.mimeType) },
                    entryId = message.entryId,
                )
            },
            execution = value.execution,
            provider = value.agent.provider,
            modelId = value.agent.modelId,
            modelName = value.agent.modelName,
            agentId = value.agent.agentId,
            thinkingLevel = null,
            sessionId = value.agent.sessionId,
            streaming = value.agent.streaming,
            steeringMessages = value.agent.steeringMessages,
            models = value.models,
            modelsRevision = value.modelsRevision,
            agents = value.agents,
            agentsRevision = value.agentsRevision,
            commands = value.commands,
            commandsRevision = value.commandsRevision,
            extensionUiRequests = value.extensionUiRequests,
            extensionStatuses = value.extensionStatuses,
            extensionWidgets = value.extensionWidgets,
            thinkingLevels = value.thinkingLevels,
            thinkingLevelsRevision = value.thinkingLevelsRevision,
            stats = value.stats,
            error = value.failure?.message,
        )
    }

    companion object {
        fun factory(session: OcSession, runtime: RuntimeConnection) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(session, runtime) as T
            }
    }
}
