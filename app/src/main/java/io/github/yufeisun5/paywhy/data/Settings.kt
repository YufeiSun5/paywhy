package io.github.yufeisun5.paywhy.data

import android.content.Context
import io.github.yufeisun5.paywhy.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class Settings(
    val memberName: String,
    val memberId: String,
    val deviceId: String,
    val recipient: String,
    val clientId: String,
    val captureWechat: Boolean,
    val captureAlipay: Boolean,
    val autoSend: Boolean,
    val wifiOnly: Boolean,
    val sendTime: String,
    val zone: String,
    val startDate: String,
    val residentMode: Boolean = false,
    val mailProvider: String = "microsoft",
    val qqAddress: String = "",
) {
    val captureEnabled get() = captureWechat || captureAlipay
    fun validate() {
        require(memberName.isNotBlank() && memberName.length <= 24 && memberName.none { it.isISOControl() }) { "成员名称须为 1–24 个可见字符" }
        require(Regex("[A-Za-z0-9_-]{4,40}").matches(memberId)) { "成员 ID 须为 4–40 位字母、数字、下划线或短横线" }
        require(recipient.isBlank() || Regex("[^\\s@<>;,]+@[^\\s@<>;,]+\\.[^\\s@<>;,]+").matches(recipient)) { "请输入一个有效的收件邮箱" }
        require(clientId.isBlank() || runCatching { UUID.fromString(clientId) }.isSuccess) { "Client ID 格式不正确" }
        require(runCatching { LocalTime.parse(sendTime) }.isSuccess) { "时间格式应为 HH:mm，例如 08:00" }
        require(runCatching { ZoneId.of(zone) }.isSuccess) { "时区格式不正确，例如 Asia/Shanghai" }
        require(mailProvider in setOf("qq", "microsoft")) { "请选择发送方式" }
        require(qqAddress.isBlank() || Regex("[A-Za-z0-9._+-]+@(qq|foxmail)\\.com", RegexOption.IGNORE_CASE).matches(qqAddress)) { "请输入完整 QQ / Foxmail 邮箱地址" }
        require(!autoSend || recipient.isNotBlank() && (if (mailProvider == "qq") qqAddress.isNotBlank() else clientId.isNotBlank())) { "请先配置收件邮箱和发件账户" }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())
    val flow = current.asStateFlow()
    val value get() = current.value
    init { persist(value) }
    private fun read(): Settings {
        val zone = prefs.getString("zone", null) ?: ZoneId.systemDefault().id
        return Settings(
            prefs.getString("name", "我")!!,
            prefs.getString("member", null) ?: "m_" + UUID.randomUUID().toString().replace("-", "").take(12),
            prefs.getString("device", null) ?: UUID.randomUUID().toString(),
            prefs.getString("recipient", "")!!, prefs.getString("client", BuildConfig.MICROSOFT_CLIENT_ID)!!,
            prefs.getBoolean("wechat", false), prefs.getBoolean("alipay", false),
            prefs.getBoolean("auto", false), prefs.getBoolean("wifi", false),
            prefs.getString("time", "08:00")!!, zone,
            prefs.getString("start", LocalDate.now(ZoneId.of(zone)).toString())!!,
            prefs.getBoolean("resident", false),
            prefs.getString("mail_provider", null) ?: if ((prefs.getString("client", BuildConfig.MICROSOFT_CLIENT_ID) ?: "").isNotBlank() || prefs.getBoolean("auto", false)) "microsoft" else "qq",
            prefs.getString("qq_address", "")!!,
        )
    }
    @Synchronized fun save(s: Settings) { s.validate(); persist(s); current.value = s }
    private fun persist(s: Settings) {
        check(prefs.edit().putString("name", s.memberName).putString("member", s.memberId)
            .putString("device", s.deviceId).putString("recipient", s.recipient).putString("client", s.clientId)
            .putBoolean("wechat", s.captureWechat).putBoolean("alipay", s.captureAlipay)
            .putBoolean("auto", s.autoSend).putBoolean("wifi", s.wifiOnly).putString("time", s.sendTime)
            .putString("zone", s.zone).putString("start", s.startDate).putBoolean("resident", s.residentMode)
            .putString("mail_provider", s.mailProvider).putString("qq_address", s.qqAddress)
            .commit()) { "无法保存设置" }
    }
}
