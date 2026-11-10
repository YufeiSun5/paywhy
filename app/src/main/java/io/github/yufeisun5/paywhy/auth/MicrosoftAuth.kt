package io.github.yufeisun5.paywhy.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import io.github.yufeisun5.paywhy.core.sha256
import io.github.yufeisun5.paywhy.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.*
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LoginRequired : Exception("微软登录已失效，请重新登录后重试。")
data class LoginStatus(val authorized: Boolean = false, val account: String = "未登录", val identity: String = "")

class MicrosoftAuth(context: Context, private val settings: SettingsStore) {
    private val secrets = SecretStore(context)
    private val service = AuthorizationService(context)
    private val mutex = Mutex()
    private var state = AuthState()
    private var clientId = ""
    private var identity = ""
    private var account = "未登录"
    private val status = MutableStateFlow(LoginStatus())
    val flow = status.asStateFlow()
    val value get() = status.value
    init {
        runCatching {
            secrets.read()?.let { raw ->
                val saved = JSONObject(raw)
                state = AuthState.jsonDeserialize(saved.getString("state"))
                clientId = saved.getString("client")
                identity = saved.getString("identity")
                account = saved.getString("account")
            }
        }.onFailure { state = AuthState(); account = "登录凭据不可用，请重新登录" }
        publish()
    }

    fun loginIntent(): Intent {
        val id = settings.value.clientId
        require(id.isNotBlank()) { "请先保存微软应用 Client ID" }
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
            Uri.parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"),
        )
        val request = AuthorizationRequest.Builder(config, id, ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI))
            .setScope("openid profile email offline_access https://graph.microsoft.com/Mail.Send")
            .setPrompt("select_account")
            .build() // AppAuth creates state, nonce, and an S256 PKCE verifier/challenge.
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun complete(intent: Intent) = mutex.withLock {
        val response = AuthorizationResponse.fromIntent(intent)
            ?: throw Exception("登录未完成，请在系统浏览器中完成微软授权。")
        require(response.request.clientId == settings.value.clientId) { "应用配置已变更，请重新登录" }
        val token = suspendCancellableCoroutine<TokenResponse> { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { value, error ->
                if (cont.isActive) {
                    if (value != null) cont.resume(value)
                    else cont.resumeWithException(Exception("微软授权交换失败（${error?.error ?: "network"}），请重试。"))
                }
            }
        }
        val claims = token.idToken?.split('.')?.getOrNull(1)?.let {
            JSONObject(String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        } ?: throw Exception("微软未返回账户标识，请检查应用的 openid 权限配置。")
        require(claims.optString("sub").isNotBlank()) { "微软账户标识缺失" }
        state = AuthState(response, null).apply { update(token, null) }
        clientId = response.request.clientId
        identity = sha256(clientId + "|" + claims.getString("sub"))
        account = claims.optString("preferred_username").ifBlank { claims.optString("email").ifBlank { "微软个人账户" } }
        persist()
    }

    suspend fun token(expectedIdentity: String): String = mutex.withLock {
        if (!state.isAuthorized || clientId != settings.value.clientId || identity != expectedIdentity) throw LoginRequired()
        try {
            suspendCancellableCoroutine<String> { cont ->
                state.performActionWithFreshTokens(service) { access, _, error ->
                    if (cont.isActive) {
                        if (access != null && error == null) cont.resume(access)
                        else if (error?.error in setOf("invalid_grant", "interaction_required", "invalid_client"))
                            cont.resumeWithException(LoginRequired())
                        else cont.resumeWithException(IOException("微软授权服务暂时不可用"))
                    }
                }
            }
        } finally {
            persist()
        }
    }

    suspend fun forget() = mutex.withLock {
        secrets.clear(); state = AuthState(); identity = ""; clientId = ""; account = "未登录"; publish()
    }
    private fun persist() {
        secrets.write(JSONObject().put("state", state.jsonSerializeString()).put("client", clientId)
            .put("identity", identity).put("account", account).toString())
        publish()
    }
    private fun publish() { status.value = LoginStatus(state.isAuthorized && clientId == settings.value.clientId, account, identity) }
    companion object { const val REDIRECT_URI = "io.github.yufeisun5.paywhy:/oauth2redirect" }
}
