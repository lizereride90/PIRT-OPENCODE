package io.github.zixt233.pirt.runtime.oc

import org.json.JSONArray
import org.json.JSONObject

enum class ProcessState { STOPPED, STARTING, RUNNING, EXITED, CRASHED }

enum class TurnState { IDLE, QUEUED, GENERATING, RUNNING_TOOL, COMPACTING, STOPPING, COMPLETED, FAILED }

enum class OcMessageRole { USER, ASSISTANT, SYSTEM }

data class OcImage(val data: String, val mimeType: String)

data class OcMessage(
    val id: String,
    val role: OcMessageRole,
    val text: String,
    val images: List<OcImage> = emptyList(),
    val entryId: String? = null,
)

data class OcModel(
    val provider: String,
    val id: String,
    val name: String,
    val reasoning: Boolean = false,
)

data class OcAgent(
    val id: String,
    val name: String,
    val description: String = "",
)

data class OcCommand(val name: String, val description: String, val source: String)

data class OcPermissionRequest(
    val id: String,
    val method: String,
    val title: String = "",
    val message: String = "",
    val options: List<String> = emptyList(),
    val placeholder: String = "",
    val prefill: String = "",
    val notifyType: String = "info",
    val key: String = "",
    val value: String? = null,
    val lines: List<String> = emptyList(),
)

data class OcTokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
)

data class OcContextUsage(
    val tokens: Long?,
    val contextWindow: Long,
    val percent: Double?,
)

data class OcSessionStats(
    val tokens: OcTokenUsage = OcTokenUsage(),
    val contextUsage: OcContextUsage? = null,
)

sealed interface OcExecutionItem {
    val id: String
}

data class OcThinkingState(
    override val id: String,
    val text: String = "",
    val finished: Boolean = false,
) : OcExecutionItem

data class OcToolState(
    override val id: String,
    val name: String,
    val summary: String,
    val input: String = "",
    val output: String = "",
    val images: List<OcImage> = emptyList(),
    val finished: Boolean = false,
    val failed: Boolean = false,
) : OcExecutionItem

data class OcAgentRuntimeState(
    val provider: String = "",
    val modelId: String = "",
    val modelName: String = "",
    val agentId: String = "",
    val streaming: Boolean = false,
    val compacting: Boolean = false,
    val sessionFile: String? = null,
    val sessionId: String? = null,
    val pendingMessageCount: Int = 0,
    val autoCompactionEnabled: Boolean = true,
    val steeringMessages: List<String> = emptyList(),
)

data class OcSessionState(
    val process: ProcessState = ProcessState.STOPPED,
    val turn: TurnState = TurnState.IDLE,
    val historyLoaded: Boolean = false,
    val agentLoaded: Boolean = false,
    val messages: List<OcMessage> = emptyList(),
    val execution: List<OcExecutionItem> = emptyList(),
    val agent: OcAgentRuntimeState = OcAgentRuntimeState(),
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
    val failure: OcFailure? = null,
)

data class OcSessionSummary(
    val process: ProcessState,
    val turn: TurnState,
)

data class OcBranchResult(val sessionKey: String)

sealed interface OcFailure {
    val message: String

    data class Command(val command: String, override val message: String) : OcFailure
    data class Protocol(override val message: String) : OcFailure
    data class Process(override val message: String, val exitCode: Int? = null) : OcFailure
    data class Timeout(val command: String, override val message: String) : OcFailure
}

class OcRequestException(val failure: OcFailure) : Exception(failure.message)

/** Defensive parsers for the opencode serve REST shapes. Unknown fields are ignored. */
internal fun JSONObject.optModelList(providerFallback: String = ""): List<OcModel> = buildList {
    val models = optJSONObject("models") ?: optJSONArray("models") ?: return@buildList
    when (models) {
        is JSONObject -> {
            models.keys().forEach { id ->
                val def = models.optJSONObject(id)
                add(OcModel(
                    provider = optString("id").ifBlank { providerFallback },
                    id = id,
                    name = def?.optString("name").orEmpty().ifBlank { id },
                ))
            }
        }
        is JSONArray -> {
            for (i in 0 until models.length()) {
                val def = models.optJSONObject(i) ?: continue
                val id = def.optString("id").ifBlank { continue }
                add(OcModel(
                    provider = def.optString("providerID", providerFallback),
                    id = id,
                    name = def.optString("name").ifBlank { id },
                ))
            }
        }
    }
}

internal fun decodeOcParts(parts: JSONArray): Pair<String, String> {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        when (part.optString("type")) {
            "text" -> text.append(part.optString("text"))
            "reasoning" -> reasoning.append(part.optString("text"))
        }
    }
    return text.toString() to reasoning.toString()
}
