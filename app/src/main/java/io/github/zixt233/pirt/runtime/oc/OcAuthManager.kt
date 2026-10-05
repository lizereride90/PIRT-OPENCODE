package io.github.zixt233.pirt.runtime.oc

import android.content.Context
import io.github.zixt233.pirt.model.WorkspaceConfig
import io.github.zixt233.pirt.runtime.PRootRuntime
import io.github.zixt233.pirt.runtime.RuntimeDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

enum class OcAuthProcessState { STOPPED, STARTING, READY, FAILED }
enum class OcAuthActivity { STARTING, LOADING_PROVIDERS, LOADING_MODELS, LOGGING_IN, LOGGING_OUT, SELECTING_MODEL }

data class OcProvider(
    val id: String,
    val name: String,
    val configured: Boolean = false,
)

data class OcAuthOption(val id: String, val label: String, val description: String? = null)

sealed interface OcAuthEvent {
    data class Prompt(
        val loginId: String,
        val promptId: String,
        val kind: String,
        val message: String,
        val placeholder: String? = null,
        val options: List<OcAuthOption> = emptyList(),
        val title: String = "",
        val prefill: String = "",
    ) : OcAuthEvent

    data class Notice(
        val loginId: String,
        val kind: String,
        val message: String? = null,
        val url: String? = null,
        val userCode: String? = null,
        val verificationUri: String? = null,
        val title: String = "",
    ) : OcAuthEvent
}

data class OcAuthState(
    val process: OcAuthProcessState = OcAuthProcessState.STOPPED,
    val providers: List<OcProvider> = emptyList(),
    val providersLoaded: Boolean = false,
    val models: List<OcModel> = emptyList(),
    val modelsRevision: Long = 0,
    val selectedProvider: String? = null,
    val selectedModel: String? = null,
    val prompt: OcAuthEvent.Prompt? = null,
    val notice: OcAuthEvent.Notice? = null,
    val selectionRevision: Long = 0,
    val error: String? = null,
    val activity: OcAuthActivity? = null,
    val activeLoginId: String? = null,
)

/** Service-owned OpenCode authentication state and command boundary. */
class OcAuthManager(
    context: Context,
    private val workspace: WorkspaceConfig,
    private val onActivityChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val runtime = PRootRuntime(appContext)
    private val _state = MutableStateFlow(OcAuthState())
    val state: StateFlow<OcAuthState> = _state.asStateFlow()

    private val serverPassword = OcServeClient.randomPassword()
    val serve = OcServeClient(
        processSpec = { runtime.opencodeServeProcess(workspace, serverPassword) },
        serverPassword = { serverPassword },
        onLog = { message, error ->
            if (error == null) RuntimeDiagnostics.info(appContext, "oc-serve", message)
            else RuntimeDiagnostics.error(appContext, "oc-serve", message, error)
        },
    )
    @Volatile private var serveStarted = false
    @Volatile private var activeLoginId: String? = null
    private val pendingApiKeyProvider = mutableMapOf<String, String>()
    private val pendingOauthProvider = mutableMapOf<String, String>()

    @Synchronized
    fun start() {
        if (serveStarted) return
        serveStarted = true
        _state.update { it.copy(process = OcAuthProcessState.STARTING, activity = OcAuthActivity.STARTING, error = null) }
        runCatching {
            runtime.refreshNetworkConfiguration()
            serve.start()
            Thread({
                var ready = false
                repeat(120) {
                    if (serve.isReady()) {
                        ready = true
                        return@repeat
                    }
                    Thread.sleep(500)
                }
                if (ready) {
                    _state.update { it.copy(process = OcAuthProcessState.READY, activity = OcAuthActivity.LOADING_PROVIDERS) }
                    loadProviders()
                } else {
                    fail("opencode serve 启动失败", null)
                }
                onActivityChanged()
            }, "pirt-oc-auth-start").apply { isDaemon = true }.start()
        }.onFailure { error ->
            fail(error.message ?: "认证服务启动失败", error)
            onActivityChanged()
        }
    }

    fun loadProviders() {
        _state.update { it.copy(activity = OcAuthActivity.LOADING_PROVIDERS, error = null) }
        Thread({
            runCatching {
                ensureServe()
                val response = serve.get("/provider")
                val all = response.optJSONArray("all") ?: org.json.JSONArray()
                val connected = response.optJSONArray("connected")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.toSet()
                } ?: emptySet()
                val defaults = response.optJSONObject("default")
                val providers = (0 until all.length()).mapNotNull { i ->
                    val provider = all.optJSONObject(i) ?: return@mapNotNull null
                    val id = provider.optString("id").ifBlank { return@mapNotNull null }
                    OcProvider(
                        id = id,
                        name = provider.optString("name").ifBlank { id },
                        configured = id in connected,
                    )
                }
                _state.update {
                    it.copy(
                        providers = providers,
                        providersLoaded = true,
                        activity = null,
                        error = null,
                        selectedProvider = it.selectedProvider ?: defaults?.optString("provider"),
                        selectedModel = it.selectedModel ?: defaults?.optString("model"),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(activity = null, error = error.message ?: "读取服务失败") }
            }
            onActivityChanged()
        }, "pirt-oc-providers").apply { isDaemon = true }.start()
    }

    fun loadModels(providerId: String? = null) {
        _state.update { it.copy(activity = OcAuthActivity.LOADING_MODELS, error = null) }
        Thread({
            runCatching {
                ensureServe()
                val providers = serve.get("/config/providers")
                val models = flattenModels(providers, providerId)
                _state.update {
                    it.copy(
                        models = models,
                        modelsRevision = it.modelsRevision + 1,
                        activity = null,
                        error = null,
                        selectedProvider = providerId ?: it.selectedProvider,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(activity = null, error = error.message ?: "读取模型失败") }
            }
            onActivityChanged()
        }, "pirt-oc-models").apply { isDaemon = true }.start()
    }

    fun login(providerId: String, authType: String, loginMethod: String? = null) {
        if (!prepareNetworkForLogin()) return
        val loginId = "login:${System.currentTimeMillis()}"
        activeLoginId = loginId
        _state.update {
            it.copy(prompt = null, notice = null, activity = OcAuthActivity.LOGGING_IN, error = null, activeLoginId = loginId)
        }
        Thread({
            runCatching {
                ensureServe()
                val methods = serve.get("/provider/auth").optJSONArray(providerId)
                val kinds = (0 until (methods?.length() ?: 0)).map { methods!!.optJSONObject(it)?.optString("type").orEmpty() }
                val wantsOauth = authType.equals("oauth", ignoreCase = true) ||
                    (loginMethod?.equals("oauth", ignoreCase = true) == true) ||
                    kinds.any { it.contains("oauth", ignoreCase = true) }
                if (wantsOauth) startOauth(loginId, providerId)
                else requestApiKey(loginId, providerId)
            }.onFailure { error ->
                _state.update { it.copy(prompt = null, notice = null, activity = null, error = error.message, activeLoginId = null) }
                activeLoginId = null
            }
            onActivityChanged()
        }, "pirt-oc-login").apply { isDaemon = true }.start()
    }

    fun configureCustomProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        models: List<String> = emptyList(),
        providerId: String? = null,
    ) {
        if (!prepareNetworkForLogin()) return
        val id = sanitizeProviderId(providerId?.takeIf { it.isNotBlank() } ?: name)
        _state.update { it.copy(prompt = null, notice = null, activity = OcAuthActivity.LOGGING_IN, error = null) }
        Thread({
            runCatching {
                ensureServe()
                val modelDefs = JSONObject()
                models.filter { it.isNotBlank() }.forEach { model ->
                    modelDefs.put(model.trim(), JSONObject().put("name", model.trim()))
                }
                val entry = JSONObject()
                    .put("name", name.ifBlank { id })
                    .put("options", JSONObject().put("baseURL", baseUrl.trim()).put("apiKey", apiKey))
                if (modelDefs.length() > 0) entry.put("models", modelDefs)
                serve.patch("/config", JSONObject().put("provider", JSONObject().put(id, entry)))
                _state.update {
                    it.copy(
                        activity = null,
                        error = null,
                        selectedProvider = id,
                        selectedModel = models.firstOrNull(),
                        selectionRevision = it.selectionRevision + 1,
                    )
                }
                loadProviders()
            }.onFailure { error ->
                _state.update { it.copy(activity = null, error = error.message) }
            }
            onActivityChanged()
        }, "pirt-oc-custom").apply { isDaemon = true }.start()
    }

    fun removeCustomProvider(providerId: String) {
        _state.update { it.copy(activity = OcAuthActivity.LOGGING_OUT, error = null) }
        mutateProvider(providerId, remove = true)
    }

    fun logout(providerId: String) {
        _state.update { it.copy(activity = OcAuthActivity.LOGGING_OUT, error = null) }
        mutateProvider(providerId, remove = true)
    }

    fun selectModel(providerId: String, modelId: String) {
        _state.update { it.copy(activity = OcAuthActivity.SELECTING_MODEL, error = null) }
        Thread({
            runCatching {
                ensureServe()
                serve.patch("/config", JSONObject().put("model", "$providerId/$modelId"))
                _state.update {
                    it.copy(
                        selectedProvider = providerId,
                        selectedModel = modelId,
                        selectionRevision = it.selectionRevision + 1,
                        activity = null,
                        error = null,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(activity = null, error = error.message) }
            }
            onActivityChanged()
        }, "pirt-oc-select").apply { isDaemon = true }.start()
    }

    fun answerPrompt(promptId: String, value: String) {
        _state.update { it.copy(prompt = null, activity = OcAuthActivity.LOGGING_IN, error = null) }
        Thread({
            runCatching {
                ensureServe()
                pendingOauthProvider.remove(promptId)?.let { providerId ->
                    val callback = serve.post("/provider/$providerId/oauth/callback",
                        JSONObject().put("code", value.trim()))
                    if (!callback.optBoolean("success", true)) {
                        serve.post("/provider/$providerId/oauth/callback",
                            JSONObject().put("callbackURL", value.trim()))
                    }
                } ?: pendingApiKeyProvider.remove(promptId)?.let { providerId ->
                    serve.put("/auth/$providerId", JSONObject().put("apiKey", value))
                }
                _state.update { it.copy(activity = null, error = null, activeLoginId = null) }
                activeLoginId = null
                loadProviders()
            }.onFailure { error ->
                _state.update { it.copy(activity = null, error = error.message, activeLoginId = null) }
                activeLoginId = null
            }
            onActivityChanged()
        }, "pirt-oc-answer").apply { isDaemon = true }.start()
    }

    fun cancelLogin(loginId: String) {
        activeLoginId = null
        pendingApiKeyProvider.remove(loginId)
        pendingOauthProvider.remove(loginId)
        _state.update { it.copy(prompt = null, notice = null, activity = null, error = null, activeLoginId = null) }
    }

    fun cancelActiveLogin() {
        val id = activeLoginId ?: _state.value.activeLoginId ?: return
        cancelLogin(id)
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    @Synchronized fun activeCount(): Int = if (serveStarted) 1 else 0

    @Synchronized fun started(): Boolean = serveStarted

    @Synchronized
    fun close() {
        serve.close()
        serveStarted = false
        activeLoginId = null
        pendingApiKeyProvider.clear()
        pendingOauthProvider.clear()
        _state.value = OcAuthState()
        onActivityChanged()
    }

    private fun startOauth(loginId: String, providerId: String) {
        val authorization = serve.post("/provider/$providerId/oauth/authorize", JSONObject())
        val url = authorization.optString("url").ifBlank { authorization.optString("authorizationUrl") }
        if (url.isBlank()) {
            requestApiKey(loginId, providerId)
            return
        }
        pendingOauthProvider[loginId] = providerId
        val oauthMessage = "在浏览器打开下面的地址完成 $providerId 授权，然后把 code 粘贴回来：\n$url"
        _state.update {
            it.copy(
                prompt = OcAuthEvent.Prompt(
                    loginId = loginId,
                    promptId = loginId,
                    kind = "manual_code",
                    message = oauthMessage,
                    placeholder = "oauth code",
                ),
                activity = OcAuthActivity.LOGGING_IN,
                error = null,
            )
        }
    }

    private fun requestApiKey(loginId: String, providerId: String) {
        pendingApiKeyProvider[loginId] = providerId
        _state.update {
            it.copy(
                prompt = OcAuthEvent.Prompt(
                    loginId = loginId,
                    promptId = loginId,
                    kind = "secret",
                    message = "输入 $providerId 的 API Key（只保存在本机 opencode 配置里）。",
                    placeholder = "API Key",
                ),
                activity = OcAuthActivity.LOGGING_IN,
                error = null,
            )
        }
    }

    private fun mutateProvider(providerId: String, remove: Boolean) {
        Thread({
            runCatching {
                ensureServe()
                if (remove) {
                    serve.patch("/config", JSONObject().put("provider", JSONObject().put(providerId, JSONObject.NULL)))
                }
                _state.update { it.copy(activity = null, error = null) }
                loadProviders()
            }.onFailure { error ->
                _state.update { it.copy(activity = null, error = error.message) }
            }
            onActivityChanged()
        }, "pirt-oc-mutate").apply { isDaemon = true }.start()
    }

    private fun flattenModels(providers: JSONObject, providerId: String?): List<OcModel> {
        val list = providers.optJSONArray("providers") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val provider = list.optJSONObject(i) ?: continue
                val id = provider.optString("id")
                if (providerId != null && id != providerId) continue
                val defs = provider.optJSONObject("models") ?: continue
                defs.keys().forEach { modelId ->
                    val def = defs.optJSONObject(modelId)
                    add(OcModel(
                        provider = id,
                        id = modelId,
                        name = def?.optString("name").orEmpty().ifBlank { modelId },
                    ))
                }
            }
        }
    }

    private fun ensureServe() {
        if (!serve.isReady()) {
            serve.start()
            var ready = false
            repeat(120) {
                if (serve.isReady()) {
                    ready = true
                    return@repeat
                }
                Thread.sleep(500)
            }
            check(ready) { "opencode serve 未就绪" }
        }
    }

    private fun sanitizeProviderId(value: String): String {
        val id = value.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(48)
        return id.ifBlank { "custom" }
    }

    private fun prepareNetworkForLogin(): Boolean {
        val error = runtime.refreshNetworkConfiguration().exceptionOrNull() ?: return true
        _state.update {
            it.copy(
                prompt = null,
                notice = null,
                activity = null,
                error = "无法为 Linux 环境配置网络：${error.message ?: "未知 DNS 错误"}",
                activeLoginId = null,
            )
        }
        RuntimeDiagnostics.error(appContext, "auth", "Could not configure guest DNS", error)
        return false
    }

    private fun fail(message: String, error: Throwable?) {
        _state.update { it.copy(process = OcAuthProcessState.FAILED, providersLoaded = true, activity = null, error = message) }
        if (error == null) RuntimeDiagnostics.info(appContext, "auth", message)
        else RuntimeDiagnostics.error(appContext, "auth", message, error)
    }
}
