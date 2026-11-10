package io.github.yufeisun5.paywhy.auth

import android.content.Context
import io.github.yufeisun5.paywhy.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

class QqCredentials(context: Context, private val secrets: SecretStore =
    SecretStore(context, "qq_auth", "paywhy.qq.v1")) {
    data class Status(val address: String = "", val issue: String? = null, val revision: Int = 0)
    class Credential(val address: String, val code: String) // Never expose code via generated toString.
    private val current = MutableStateFlow(loadStatus())
    val flow = current.asStateFlow()
    val value get() = current.value
    private fun read(): Credential? = secrets.read()?.let {
        val json = JSONObject(it)
        Credential(json.getString("address"), json.getString("code"))
    }
    private fun loadStatus() = try { Status(read()?.address.orEmpty()) }
        catch (_: Exception) { Status(issue = "QQ 授权码无法解密，请重新保存") }
    fun ready(s: Settings) = value.address.isNotEmpty() && value.address == normalize(s.qqAddress)
    fun save(address: String, code: String) {
        val clean = code.filterNot { it.isWhitespace() }
        require(Regex("[A-Za-z]{16}").matches(clean)) { "请输入 QQ 邮箱生成的 16 位字母授权码" }
        try { secrets.write(JSONObject().put("address", normalize(address)).put("code", clean).toString()) }
        catch (_: Exception) { error("无法安全保存 QQ 授权码，请重试") }
        current.value = Status(normalize(address), revision = value.revision + 1)
    }
    fun forMail(address: String, identity: String): Credential {
        val c = try { read() } catch (_: Exception) { null }
        check(c != null && c.address == normalize(address) && identity == identity(c.address)) {
            "请配置创建此邮件的 QQ 邮箱及授权码，再手动重试"
        }
        return c
    }
    fun clear() { secrets.clear(); current.value = Status(revision = value.revision + 1) }
    companion object {
        fun normalize(address: String) = address.trim().lowercase(Locale.ROOT)
        fun identity(address: String) = "qq:" + normalize(address)
    }
}
