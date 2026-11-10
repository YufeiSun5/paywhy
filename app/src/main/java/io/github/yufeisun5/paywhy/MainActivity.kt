package io.github.yufeisun5.paywhy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.yufeisun5.paywhy.auth.QqCredentials
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import io.github.yufeisun5.paywhy.background.ResidentMode
import io.github.yufeisun5.paywhy.background.ResidentState
import io.github.yufeisun5.paywhy.capture.PaymentListener
import io.github.yufeisun5.paywhy.capture.MinshengSmsReceiver
import io.github.yufeisun5.paywhy.core.Minsheng
import io.github.yufeisun5.paywhy.core.amountText
import io.github.yufeisun5.paywhy.data.Observation
import io.github.yufeisun5.paywhy.data.OutboxMail
import io.github.yufeisun5.paywhy.data.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: PayWhyModel by viewModels()
    private var smsGranted by mutableStateOf(false)
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsGranted = granted
        val pending = model.pendingSmsSave
        model.pendingSmsSave = null
        pending?.let {
            if (!granted) model.message.value = "短信权限未授予，已关闭短信采集；其他设置仍会保存"
            saveWithNotificationPermission(it.settings.copy(captureMinshengSms = granted), it.qqCode)
        }
    }
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.let(model::completeLogin) ?: run { model.message.value = "登录已取消" }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = model.pendingNotificationSave
        model.pendingNotificationSave = null
        pending?.let {
            if (granted && ResidentMode.canShowNotification(this)) model.save(it.settings, it.qqCode)
            else model.save(it.settings.copy(residentMode = false), it.qqCode, "通知未获准显示，已保存其他设置并使用静默模式")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.app.settings.flow.collect { ResidentMode.apply(model.app) }
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B53),
                secondaryContainer = Color(0xFFD6EADF), surfaceVariant = Color(0xFFE5ECE5),
                surfaceContainer = Color(0xFFECF2EB), surfaceContainerHighest = Color(0xFFE5ECE5),
                background = Color(0xFFF5F7F4), surface = Color(0xFFFCFDF9))) {
                AppScreen(model, onLogin = {
                    runCatching { login.launch(model.app.auth.loginIntent()) }
                        .onFailure { model.message.value = it.message ?: "无法打开系统浏览器" }
                }, onNotifications = { openSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                    onBattery = { openSettings(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                    onSave = ::saveWithPermission, smsGranted = smsGranted,
                    onNotificationDisplay = {
                        runCatching { startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)) }
                            .onFailure { model.message.value = "请在手机应用设置中允许 PayWhy 显示通知" }
                    })
            }
        }
    }
    override fun onResume() {
        super.onResume()
        smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }
    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }.onFailure { model.message.value = "请在手机设置中手动打开对应权限页面" }
    }
    private fun saveWithPermission(s: Settings, qqCode: String) {
        runCatching { s.validate() }.onFailure { model.message.value = it.message; return }
        if (s.captureMinshengSms && ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            model.pendingSmsSave = PayWhyModel.SaveRequest(s, qqCode)
            smsPermission.launch(Manifest.permission.RECEIVE_SMS)
            return
        }
        saveWithNotificationPermission(s, qqCode)
    }
    private fun saveWithNotificationPermission(s: Settings, qqCode: String) {
        if (!s.residentMode) { model.save(s, qqCode); return }
        ResidentMode.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            model.pendingNotificationSave = PayWhyModel.SaveRequest(s, qqCode)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!ResidentMode.canShowNotification(this)) {
            model.save(s.copy(residentMode = false), qqCode, "通知显示已被系统关闭，已保存其他设置；请先允许通知，再启用常驻模式")
        } else model.save(s, qqCode)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(model: PayWhyModel, onLogin: () -> Unit, onNotifications: () -> Unit, onBattery: () -> Unit,
                      onSave: (Settings, String) -> Unit, smsGranted: Boolean, onNotificationDisplay: () -> Unit) {
    val settings by model.app.settings.flow.collectAsStateWithLifecycle()
    val auth by model.app.auth.flow.collectAsStateWithLifecycle()
    val qq by model.app.qq.flow.collectAsStateWithLifecycle()
    val mailReady = if (settings.mailProvider == "qq") qq.address.isNotBlank() && qq.address == QqCredentials.normalize(settings.qqAddress) else auth.authorized
    val records by model.records.collectAsStateWithLifecycle()
    val mails by model.mails.collectAsStateWithLifecycle()
    val count by model.count.collectAsStateWithLifecycle()
    val unexported by model.unexported.collectAsStateWithLifecycle()
    val connected by PaymentListener.connection.collectAsStateWithLifecycle()
    val resident by ResidentMode.state.collectAsStateWithLifecycle()
    val captureError by PaymentListener.lastError.collectAsStateWithLifecycle()
    val smsError by MinshengSmsReceiver.lastError.collectAsStateWithLifecycle()
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
                        Text(if (!settings.notificationCaptureEnabled) "通知采集未开启" else if (connected) "通知监听已连接" else "通知监听未连接，请检查权限")
                        if (settings.captureMinshengSms) Text(if (smsGranted) "民生信用卡短信采集已开启" else "短信权限未授予，无法采集新短信", style = MaterialTheme.typography.bodySmall)
                        Text("每天 ${settings.sendTime} · ${settings.zone}", style = MaterialTheme.typography.bodySmall)
                        Text(if (settings.autoSend) "每日邮件已开启，省电或断网时可能延迟" else "每日邮件未开启", style = MaterialTheme.typography.bodySmall)
                        Text(modeStatus(settings, resident), style = MaterialTheme.typography.bodySmall)
                        captureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        smsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && mailReady, onClick = { model.queue(false) }) { Text("发送新记录") }
                            OutlinedButton(onClick = { page = 2 }) { Text("配置") }
                        }
                    } }
                    item { Text("近期采集", style = MaterialTheme.typography.titleMedium) }
                    if (records.isEmpty()) item { Panel {
                        Text("还没有支付记录")
                        Text("在设置中选择支付通知或民生信用卡短信，授予对应权限。只采集开启后新收到的消息，不读取历史短信。")
                        Text("记录只保存在手机。邮件发送后，本地记录仍然保留。", style = MaterialTheme.typography.bodySmall)
                    } }
                    items(records, key = { it.id }) { RecordCard(it) }
                    if (records.size == 100) item { Text("显示最近 100 条；较早记录仍保存在本地并参与导出。") }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("邮件投递", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Panel {
                        Text("收件：${settings.recipient.ifBlank { "尚未设置" }}")
                        Text("服务器已接受 ≠ 已进入收件箱。请检查收件箱和垃圾邮件。", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && mailReady, onClick = { model.queue(true) }) { Text("发送测试邮件") }
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
                    onSave, onLogin, model::signOut, onNotifications, onBattery, resident, onNotificationDisplay, qq, smsGranted)
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
                           onSave: (Settings, String) -> Unit, onLogin: () -> Unit, onSignOut: () -> Unit,
                           onNotifications: () -> Unit, onBattery: () -> Unit,
                           resident: ResidentState, onNotificationDisplay: () -> Unit, qq: QqCredentials.Status, smsGranted: Boolean) {
    var draft by remember(s) { mutableStateOf(s) }
    var qqCode by remember { mutableStateOf("") }
    LaunchedEffect(qq.revision) { qqCode = "" }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("为自己，也为家人", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Panel {
            Text("邮件发送", style = MaterialTheme.typography.titleMedium)
            ModeOption("QQ 邮箱发送（推荐）", "填一次授权码，自动寄到你的 Live 或其他邮箱", draft.mailProvider == "qq") {
                draft = draft.copy(mailProvider = "qq")
            }
            ModeOption("微软邮箱发送", "Live / Outlook 登录授权，需要配置应用 Client ID", draft.mailProvider == "microsoft") {
                draft = draft.copy(mailProvider = "microsoft"); qqCode = ""
            }
            Field("收件邮箱（例如你的 Live 邮箱）", draft.recipient) { draft = draft.copy(recipient = it.trim()) }
            if (draft.mailProvider == "qq") {
                Field("发件 QQ 邮箱（完整地址）", draft.qqAddress) { draft = draft.copy(qqAddress = QqCredentials.normalize(it)) }
                OutlinedTextField(value = qqCode, onValueChange = { qqCode = it },
                    label = { Text("QQ 邮箱授权码（16 位字母）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                val configured = qq.address.isNotBlank() && qq.address == QqCredentials.normalize(draft.qqAddress)
                Text(if (configured) "授权码已加密保存；留空保留，输入新码可替换。尚需测试收件。" else "尚未保存此邮箱的授权码。这里填写邮箱授权码。")
                qq.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("获取授权码：登录 QQ 邮箱网页版 → 设置 → 开启 SMTP 服务（通常在账户或账号与安全中）→ 验证后生成授权码。", style = MaterialTheme.typography.bodySmall)
                Text("授权码只加密保存在手机。保存后到「投递」发送测试邮件；成功收件后开启每日发送。", style = MaterialTheme.typography.bodySmall)
                if (qq.address.isNotBlank() && s.mailProvider == "qq") TextButton(enabled = !busy, onClick = onSignOut) { Text("清除 QQ 授权码并关闭每日发送") }
            } else {
                Text("通过微软官方页面授权，PayWhy 不读取邮箱密码或收件箱。", style = MaterialTheme.typography.bodySmall)
                Field("微软应用 Client ID", draft.clientId) { draft = draft.copy(clientId = it.trim()) }
                Text("应用注册步骤见仓库 README，登录前请先保存设置。", style = MaterialTheme.typography.bodySmall)
                Text(if (authorized) "已连接：$account" else "未连接微软邮箱")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && draft == s && s.clientId.isNotBlank(), onClick = onLogin) { Text("微软登录") }
                    if (authorized && s.mailProvider == "microsoft") TextButton(enabled = !busy, onClick = onSignOut) { Text("退出登录") }
                }
            }
        } }
        item { Panel {
            Text("后台运行模式", style = MaterialTheme.typography.titleMedium)
            ModeOption("静默模式（默认）", "不显示常驻通知，按系统调度采集和发信", !draft.residentMode) {
                draft = draft.copy(residentMode = false)
            }
            ModeOption("常驻通知模式", "前台服务显示状态与快捷入口，适合希望查看运行状态时使用", draft.residentMode) {
                draft = draft.copy(residentMode = true)
            }
            Text(modeStatus(s, resident), style = MaterialTheme.typography.bodySmall)
            Text("两种模式都按事件采集、每天集中发信。常驻模式不持有唤醒锁、不提高检查频率；实际功耗和后台存活仍受手机系统影响。", style = MaterialTheme.typography.bodySmall)
            Text("首次常驻需要允许通知。高版本 Android 可从通知切回静默或停止应用；强行停止后需重新打开。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onNotificationDisplay) { Text("通知显示设置") }
            if (draft.residentMode != s.residentMode) Text("请保存设置使模式生效", color = MaterialTheme.colorScheme.primary)
        } }
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
            Toggle("采集民生借记卡 App 通知", draft.captureMinsheng) { draft = draft.copy(captureMinsheng = it) }
            OutlinedButton(onClick = onNotifications) { Text("打开系统通知使用权") }
            Toggle("采集民生信用卡交易短信", draft.captureMinshengSms) { draft = draft.copy(captureMinshengSms = it) }
            Text("保存时申请接收短信权限；仅保存已适配民生号码发来的交易短信，不读取历史短信、不保存验证码或账单提醒。", style = MaterialTheme.typography.bodySmall)
            if (draft.captureMinshengSms) Text(if (smsGranted) "短信权限已授予" else "短信权限待授予；若系统不再弹窗，请到应用权限设置开启")
            Text("美元等外币保留原币种，不换算。预授权、冲正与还款单独标识；只有“支出”的借记卡提醒保留为待核对资金流出。", style = MaterialTheme.typography.bodySmall)
            Text("GitHub 下载的 APK 在高版本 Android 上可能需要先到应用信息中允许受限制的设置。", style = MaterialTheme.typography.bodySmall)
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
        item { Text("PayWhy ${BuildConfig.VERSION_NAME} · 本地优先 · 开源\nUTF-8 纯文本邮件与附件；无自建服务器、广告或分析 SDK。卸载将删除本机记录与登录凭据，请先投递需要保留的数据。", style = MaterialTheme.typography.bodySmall) }
    }
    Surface(tonalElevation = 2.dp) {
        Button(onClick = { onSave(draft, qqCode) }, enabled = !busy && (draft != s || qqCode.isNotBlank() || draft.captureMinshengSms && !smsGranted),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) { Text("保存设置") }
    }
    }
}

@Composable private fun ModeOption(title: String, detail: String, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = select)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
private fun modeStatus(settings: Settings, state: ResidentState): String = when {
    !settings.residentMode -> "当前：静默运行，无常驻通知"
    state.running -> "当前：常驻通知服务运行中"
    else -> state.issue ?: "当前：常驻模式待启动，请保持应用在前台"
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
        Text(row.memberName + " · " + when (row.source) {
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            Minsheng.APP -> "民生借记卡"
            Minsheng.SMS -> "民生信用卡短信"
            else -> "其他来源"
        }, fontWeight = FontWeight.SemiBold)
        Text(amountText(row.amountMinor)?.let { "${row.currency ?: "币种待核对"} $it" } ?: "金额待核对", fontWeight = FontWeight.Bold)
    }
    Text(row.title, style = MaterialTheme.typography.bodyMedium)
    Text(row.text, style = MaterialTheme.typography.bodySmall)
    Text(when (row.kind) {
        "authorization" -> "预授权／消费通知，未确认最终入账"
        "reversal" -> "消费冲正，不是新增消费"
        "transfer" -> "转账／还款"
        "refund" -> "退款"
        "outflow" -> "资金流出，用途待核对"
        "inflow" -> "资金流入，用途待核对"
        "expense" -> "消费提示"
        "income" -> "收入提示"
        else -> "交易类型待核对"
    }, style = MaterialTheme.typography.labelSmall)
    Text("${formatTime(row.capturedAt)} · ${if (row.review == "needs_review") "待核对" else "规则识别，未核实"}", style = MaterialTheme.typography.labelSmall)
    if (row.possibleDuplicateOf != null) Text("可能与另一条记录重复，已保留供后续核对", style = MaterialTheme.typography.labelSmall)
} }
private fun formatTime(time: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun kindLabel(kind: String) = when (kind) { "TEST" -> "测试邮件"; "DAILY" -> "每日邮件"; else -> "手动发送" }
private fun statusLabel(status: String) = when (status) {
    "pending" -> "等待发送"; "retry" -> "等待重试"; "sending" -> "发送中"; "accepted" -> "服务器已接受"
    "uncertain" -> "结果不确定"; else -> "需要处理"
}
