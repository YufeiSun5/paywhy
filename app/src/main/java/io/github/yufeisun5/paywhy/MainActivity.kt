package io.github.yufeisun5.paywhy

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yufeisun5.paywhy.capture.PaymentListener
import io.github.yufeisun5.paywhy.core.amountText
import io.github.yufeisun5.paywhy.data.Observation
import io.github.yufeisun5.paywhy.data.OutboxMail
import io.github.yufeisun5.paywhy.data.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model: PayWhyModel by viewModels()
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.let(model::completeLogin) ?: run { model.message.value = "登录已取消" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B53),
                secondaryContainer = Color(0xFFD6EADF), surfaceVariant = Color(0xFFE5ECE5),
                surfaceContainer = Color(0xFFECF2EB), surfaceContainerHighest = Color(0xFFE5ECE5),
                background = Color(0xFFF5F7F4), surface = Color(0xFFFCFDF9))) {
                AppScreen(model, onLogin = {
                    runCatching { login.launch(model.app.auth.loginIntent()) }
                        .onFailure { model.message.value = it.message ?: "无法打开系统浏览器" }
                }, onNotifications = { openSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                    onBattery = { openSettings(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
            }
        }
    }
    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { model.message.value = "请在手机设置中手动打开对应权限页面" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(model: PayWhyModel, onLogin: () -> Unit, onNotifications: () -> Unit, onBattery: () -> Unit) {
    val settings by model.app.settings.flow.collectAsStateWithLifecycle()
    val auth by model.app.auth.flow.collectAsStateWithLifecycle()
    val records by model.records.collectAsStateWithLifecycle()
    val mails by model.mails.collectAsStateWithLifecycle()
    val count by model.count.collectAsStateWithLifecycle()
    val unexported by model.unexported.collectAsStateWithLifecycle()
    val connected by PaymentListener.connection.collectAsStateWithLifecycle()
    val captureError by PaymentListener.lastError.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<OutboxMail?>(null) }
    var retryMail by remember { mutableStateOf<OutboxMail?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); model.message.value = null } }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("PayWhy", fontWeight = FontWeight.Bold); Text("把记录寄给未来的自己", style = MaterialTheme.typography.labelMedium) } }) },
        bottomBar = { NavigationBar { listOf("记录", "投递", "设置").forEachIndexed { i, text ->
            NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Text(listOf("◫", "↗", "⚙")[i]) }, label = { Text(text) })
        } } },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (page) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("${settings.memberName}的记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Panel {
                        Text("已保存 $count 条 · 待导出 $unexported 条", style = MaterialTheme.typography.titleMedium)
                        Text(if (!settings.captureEnabled) "采集尚未开启" else if (connected) "通知监听已连接" else "通知监听未连接，请检查权限")
                        Text("每天 ${settings.sendTime} · ${settings.zone}", style = MaterialTheme.typography.bodySmall)
                        Text(if (settings.autoSend) "每日邮件已开启，省电或断网时可能延迟" else "每日邮件未开启", style = MaterialTheme.typography.bodySmall)
                        captureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && auth.authorized, onClick = { model.queue(false) }) { Text("发送新记录") }
                            OutlinedButton(onClick = { page = 2 }) { Text("配置") }
                        }
                    } }
                    item { Text("近期采集", style = MaterialTheme.typography.titleMedium) }
                    if (records.isEmpty()) item { Panel {
                        Text("还没有支付记录")
                        Text("先在设置中开启微信或支付宝采集，再授予通知使用权。只有系统实际发出的相关通知才能被采集。")
                        Text("记录只保存在手机。邮件发送后，本地记录仍然保留。", style = MaterialTheme.typography.bodySmall)
                    } }
                    items(records, key = { it.id }) { RecordCard(it) }
                    if (records.size == 100) item { Text("显示最近 100 条；较早记录仍保存在本地并参与导出。") }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("邮件投递", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Panel {
                        Text("收件：${settings.recipient.ifBlank { "尚未设置" }}")
                        Text("已由微软接受 ≠ 已进入收件箱。请检查收件箱、垃圾邮件和已发送邮件。", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && auth.authorized, onClick = { model.queue(true) }) { Text("发送测试邮件") }
                    } }
                    if (mails.isEmpty()) item { Text("尚无投递记录。测试邮件不会包含你的支付数据。") }
                    items(mails, key = { it.id }) { mail -> Panel {
                        Text("${kindLabel(mail.kind)} · ${statusLabel(mail.status)}", fontWeight = FontWeight.SemiBold)
                        Text(mail.subject, style = MaterialTheme.typography.bodySmall)
                        Text("${formatTime(mail.createdAt)} · 尝试 ${mail.attempts} 次", style = MaterialTheme.typography.labelSmall)
                        mail.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { preview = mail }) { Text("查看纯文本") }
                            if (mail.status in setOf("failed", "uncertain", "retry")) TextButton(enabled = !busy, onClick = { retryMail = mail }) { Text("重试") }
                        }
                    } }
                }
                2 -> SettingsScreen(settings, count, busy, auth.authorized, auth.account,
                    model::save, onLogin, model::signOut, onNotifications, onBattery)
            }
        }
    }
    preview?.let { mail -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("纯文本预览") },
        text = { LazyColumn { item { SelectionContainer { Text(mail.body.take(16000), style = MaterialTheme.typography.bodySmall) } }
            if (mail.body.length > 16000) item { Text("预览仅显示前 16000 字；邮件包含完整附件。") } } },
        confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }) }
    retryMail?.let { mail -> AlertDialog(onDismissRequest = { retryMail = null }, title = { Text("重试这一份邮件？") },
        text = { Text("收件地址仍为 ${mail.recipient}。如果邮箱中已有该批次，重试可能重复投递；AI 应按记录 ID 去重。") },
        confirmButton = { TextButton(onClick = { model.retry(mail.id); retryMail = null }) { Text("确认重试") } },
        dismissButton = { TextButton(onClick = { retryMail = null }) { Text("取消") } }) }
}

@Composable
private fun SettingsScreen(s: Settings, count: Int, busy: Boolean, authorized: Boolean, account: String,
                           onSave: (Settings) -> Unit, onLogin: () -> Unit, onSignOut: () -> Unit,
                           onNotifications: () -> Unit, onBattery: () -> Unit) {
    var draft by remember(s) { mutableStateOf(s) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("为自己，也为家人", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Panel {
            Text("成员标识", style = MaterialTheme.typography.titleMedium)
            Field("成员名称，例如小明／妈妈", draft.memberName) { draft = draft.copy(memberName = it) }
            OutlinedTextField(value = draft.memberId, onValueChange = { draft = draft.copy(memberId = it.trim()) },
                enabled = count == 0 && !s.captureEnabled, label = { Text("稳定成员 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("不同家人使用不同 ID。同一人换机时，在首次采集前填入原 ID。名称可修改，ID 用于长期归属。", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text("设备 ID：${s.deviceId}", style = MaterialTheme.typography.labelSmall) }
        } }
        item { Panel {
            Text("支付数据入口", style = MaterialTheme.typography.titleMedium)
            Text("仅提取所选应用的支付相关通知。微信聊天、其他应用通知不保存；原文和识别结果会发送至配置的邮箱。", style = MaterialTheme.typography.bodySmall)
            Toggle("采集微信支付通知", draft.captureWechat) { draft = draft.copy(captureWechat = it) }
            Toggle("采集支付宝通知", draft.captureAlipay) { draft = draft.copy(captureAlipay = it) }
            OutlinedButton(onClick = onNotifications) { Text("打开系统通知使用权") }
            Text("GitHub 下载的 APK 在高版本 Android 上可能需要先到应用信息中允许受限制的设置。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            Text("Live / Outlook 邮箱", style = MaterialTheme.typography.titleMedium)
            Field("收件邮箱（可填写家庭共用邮箱）", draft.recipient) { draft = draft.copy(recipient = it.trim()) }
            Text("通过微软官方登录页面授权，只申请发送邮件及维持登录所需权限。PayWhy 不读取邮箱密码或收件箱。", style = MaterialTheme.typography.bodySmall)
            Field("微软应用 Client ID", draft.clientId) { draft = draft.copy(clientId = it.trim()) }
            Text("首次需要注册微软公共客户端应用，配置说明见仓库 README。Client ID 是公开标识，不是密钥。登录前请先保存设置。", style = MaterialTheme.typography.bodySmall)
            Text(if (authorized) "已连接：$account" else "未连接微软邮箱")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && draft == s && s.clientId.isNotBlank(), onClick = onLogin) { Text("微软登录") }
                if (authorized) TextButton(enabled = !busy, onClick = onSignOut) { Text("退出登录") }
            }
        } }
        item { Panel {
            Text("每日投递与功耗", style = MaterialTheme.typography.titleMedium)
            Toggle("每天自动发送", draft.autoSend) { draft = draft.copy(autoSend = it) }
            Field("发送时间 HH:mm", draft.sendTime) { draft = draft.copy(sendTime = it.trim()) }
            Field("统计时区，例如 Asia/Shanghai", draft.zone) { draft = draft.copy(zone = it.trim()) }
            Toggle("仅在非计费网络发送（通常为 Wi-Fi）", draft.wifiOnly) { draft = draft.copy(wifiOnly = it) }
            Text("按所选时区发送前一日及更早未导出的记录。无新记录也发送状态邮件，不代表当天没有消费。", style = MaterialTheme.typography.bodySmall)
            Text("默认遵循系统省电策略；不持续联网、不申请精确闹钟。系统休眠、断网或厂商限制可能导致延迟。强行停止后需重新打开应用。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBattery) { Text("查看系统电池优化设置") }
            Text("先使用默认省电设置；若实测漏采或延迟严重，再为 PayWhy 允许后台运行／自启动。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Button(onClick = { onSave(draft) }, enabled = !busy && draft != s, modifier = Modifier.fillMaxWidth()) { Text("保存设置") } }
        item { Text("PayWhy 0.1.0 · 本地优先 · 开源\nUTF-8 纯文本邮件与附件；无自建服务器、广告或分析 SDK。卸载将删除本机记录与登录凭据，请先投递需要保留的数据。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
@Composable private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp, end = 8.dp)); Switch(value, change)
    }
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun RecordCard(row: Observation) { Panel {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(row.memberName + " · " + if (row.source == "com.tencent.mm") "微信" else "支付宝", fontWeight = FontWeight.SemiBold)
        Text(amountText(row.amountMinor)?.let { "${if (row.currency == "CNY") "¥" else "币种待核对 "}$it" } ?: "金额待核对", fontWeight = FontWeight.Bold)
    }
    Text(row.title, style = MaterialTheme.typography.bodyMedium)
    Text(row.text, style = MaterialTheme.typography.bodySmall)
    Text("${formatTime(row.capturedAt)} · ${if (row.review == "needs_review") "待核对" else "规则识别，未核实"}", style = MaterialTheme.typography.labelSmall)
    if (row.possibleDuplicateOf != null) Text("可能与另一条记录重复，已保留供后续核对", style = MaterialTheme.typography.labelSmall)
} }
private fun formatTime(time: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun kindLabel(kind: String) = when (kind) { "TEST" -> "测试邮件"; "DAILY" -> "每日邮件"; else -> "手动发送" }
private fun statusLabel(status: String) = when (status) {
    "pending" -> "等待发送"; "retry" -> "等待重试"; "sending" -> "发送中"; "accepted" -> "微软已接受"
    "uncertain" -> "结果不确定"; else -> "需要处理"
}
