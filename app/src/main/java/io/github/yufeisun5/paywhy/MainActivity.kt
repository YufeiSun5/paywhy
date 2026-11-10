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
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.yufeisun5.paywhy.ui.*
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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.app.settings.flow.collect { ResidentMode.apply(model.app) }
            }
        }
        setContent {
            PayWhyTheme {
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
        PaymentListener.ensureConnected(this)
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
        topBar = { TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper.Ground),
            title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.Image(painterResource(R.drawable.pw_brand), null, Modifier.size(36.dp))
                Text("PayWhy", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            } }, actions = { Text("本地记录", Modifier.padding(end = 20.dp), style = MaterialTheme.typography.labelMedium, color = Paper.Muted) }) },
        bottomBar = { Surface(color = Paper.White, shadowElevation = 0.dp) {
            Column {
                HorizontalDivider(color = Paper.Line)
                NavigationBar(containerColor = Paper.White, tonalElevation = 0.dp) {
                    listOf("记录", "投递", "设置").forEachIndexed { i, text ->
                        NavigationBarItem(selected = page == i, onClick = { page = i },
                            icon = { Icon(painterResource(listOf(R.drawable.ic_ledger, R.drawable.ic_delivery, R.drawable.ic_preferences)[i]), null, Modifier.size(22.dp)) },
                            label = { Text(text) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Paper.Sage))
                    }
                }
            }
        } },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (page) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("收支记录", style = MaterialTheme.typography.headlineMedium)
                            Text("${settings.memberName} · 保留每笔原币种", style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
                        }
                        SmallLabel(if (settings.residentMode && resident.running) "常驻模式" else "静默模式")
                    } }
                    item { OverviewCard(settings, count, unexported, connected, smsGranted, busy, mailReady,
                        onSend = { model.queue(false) }, onConfigure = { page = 2 }) }
                    captureError?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    smsError?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    item { SectionHeading("最近记录", "点按查看原文") }
                    if (records.isEmpty()) item { Panel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SourceMark(R.drawable.pw_brand)
                            Column {
                                Text("等待第一笔记录", style = MaterialTheme.typography.titleMedium)
                                Text("开启入口后，照常付款即可。", style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
                            }
                        }
                        TextButton(onClick = { page = 2 }) { Text("设置采集入口") }
                    } }
                    items(records, key = { it.id }) { RecordCard(it) }
                    if (records.size == 100) item { Text("显示最近 100 条；较早记录仍保存在本地并参与导出。") }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Column { Text("邮件投递", style = MaterialTheme.typography.headlineMedium); Text("每日整理，寄往你的邮箱", style = MaterialTheme.typography.bodySmall, color = Paper.Muted) } }
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
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column { Text("偏好设置", style = MaterialTheme.typography.headlineMedium); Text("配置一次，日常自动记录", style = MaterialTheme.typography.bodySmall, color = Paper.Muted) } }
        item { SettingsGroup("邮件发送", s.recipient.ifBlank { "尚未配置邮箱" }, s.recipient.isBlank()) {
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
        item { SettingsGroup("后台运行", if (s.residentMode) "常驻通知 · 事件采集" else "静默模式 · 事件采集") {
            ModeOption("静默模式（默认）", "不显示常驻通知，按系统调度采集和发信", !draft.residentMode) {
                draft = draft.copy(residentMode = false)
            }
            ModeOption("常驻通知模式", "前台服务显示状态与快捷入口，适合希望查看运行状态时使用", draft.residentMode) {
                draft = draft.copy(residentMode = true)
            }
            Text(modeStatus(s, resident), style = MaterialTheme.typography.bodySmall)
            HelpNote("后台与功耗说明", "两种模式都按事件采集、每天集中发信；不持有唤醒锁、不提高检查频率。实际功耗和存活受手机系统影响。首次常驻需允许通知；强行停止后需重新打开应用。")
            OutlinedButton(onClick = onNotificationDisplay) { Text("通知显示设置") }
            if (draft.residentMode != s.residentMode) Text("请保存设置使模式生效", color = MaterialTheme.colorScheme.primary)
        } }
        item { SettingsGroup("成员与设备", s.memberName) {
            Field("成员名称，例如小明／妈妈", draft.memberName) { draft = draft.copy(memberName = it) }
            OutlinedTextField(value = draft.memberId, onValueChange = { draft = draft.copy(memberId = it.trim()) },
                enabled = count == 0 && !s.captureEnabled, label = { Text("稳定成员 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            HelpNote("家人与换机", "不同家人使用不同 ID。同一人换机时，在首次采集前填入原 ID。名称可修改，ID 用于长期归属。")
            SelectionContainer { Text("设备 ID：${s.deviceId}", style = MaterialTheme.typography.labelSmall) }
        } }
        item { SettingsGroup("采集入口", "已开启 ${listOf(s.captureWechat, s.captureAlipay, s.captureMinsheng, s.captureMinshengSms).count { it }} 个来源", !s.captureEnabled) {
            Text("只保存选中入口的交易消息", style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
            Toggle("采集微信支付通知", draft.captureWechat) { draft = draft.copy(captureWechat = it) }
            Toggle("采集支付宝通知", draft.captureAlipay) { draft = draft.copy(captureAlipay = it) }
            Toggle("采集民生借记卡 App 通知", draft.captureMinsheng) { draft = draft.copy(captureMinsheng = it) }
            OutlinedButton(onClick = onNotifications) { Text("打开系统通知使用权") }
            Toggle("采集民生信用卡交易短信", draft.captureMinshengSms) { draft = draft.copy(captureMinshengSms = it) }
            HelpNote("采集范围与隐私", "仅保存已适配民生号码的交易短信，不读取历史短信，不保存验证码、账单提醒、微信聊天或其他应用通知。所选交易原文与识别结果会寄至配置邮箱。")
            if (draft.captureMinshengSms) Text(if (smsGranted) "短信权限已授予" else "短信权限待授予；若系统不再弹窗，请到应用权限设置开启")
            HelpNote("金额与交易类型", "美元等外币保留原币种，不换算。预授权、冲正与还款单独标识；只有“支出”的借记卡提醒保留为待核对资金流出。")
            HelpNote("无法授权通知使用权？", "GitHub 下载的 APK 在高版本 Android 上可能需要先到应用信息中允许受限制的设置。")
        } }
        item { SettingsGroup("每日投递", if (s.autoSend) "每天 ${s.sendTime} · ${s.zone}" else "自动投递未开启") {
            Toggle("每天自动发送", draft.autoSend) { draft = draft.copy(autoSend = it) }
            Text("到点发送此前尚未导出的记录；之后的新记录留到下一次。系统省电可能延迟投递。", style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
            Field("发送时间 HH:mm", draft.sendTime) { draft = draft.copy(sendTime = it.trim()) }
            Field("统计时区，例如 Asia/Shanghai", draft.zone) { draft = draft.copy(zone = it.trim()) }
            Toggle("仅在非计费网络发送（通常为 Wi-Fi）", draft.wifiOnly) { draft = draft.copy(wifiOnly = it) }
            Text("按所选时区发送前一日及更早未导出的记录。无新记录也发送状态邮件，不代表当天没有消费。", style = MaterialTheme.typography.bodySmall)
            HelpNote("投递延迟与省电", "默认遵循系统省电策略，不持续联网、不申请精确闹钟。休眠、断网或厂商限制可能导致延迟。强行停止后需重新打开应用。")
            OutlinedButton(onClick = onBattery) { Text("查看系统电池优化设置") }
            Text("先使用默认省电设置；若实测漏采或延迟严重，再为 PayWhy 允许后台运行／自启动。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("PayWhy ${BuildConfig.VERSION_NAME} · 本地优先 · 开源\nUTF-8 纯文本邮件与附件；无自建服务器、广告或分析 SDK。卸载将删除本机记录与登录凭据，请先投递需要保留的数据。", style = MaterialTheme.typography.bodySmall) }
    }
    Surface(color = Paper.Ground) {
        Button(onClick = { onSave(draft, qqCode) }, enabled = !busy && (draft != s || qqCode.isNotBlank() || draft.captureMinshengSms && !smsGranted),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp)) { Text("保存设置") }
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Paper.White), border = BorderStroke(1.dp, Paper.Line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable private fun SettingsGroup(title: String, summary: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Paper.White),
        border = BorderStroke(1.dp, Paper.Line)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = Paper.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (open) "−" else "+", fontSize = 21.sp, color = Paper.Muted, modifier = Modifier.padding(start = 12.dp))
            }
            if (open) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Paper.Line)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable private fun HelpNote(title: String, detail: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Paper.Muted, modifier = Modifier.weight(1f))
            Text(if (expanded) "−" else "+", color = Paper.Muted)
        }
        if (expanded) Text(detail, style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
    }
}

@Composable private fun OverviewCard(s: Settings, count: Int, pending: Int, connected: Boolean,
    smsGranted: Boolean, busy: Boolean, mailReady: Boolean, onSend: () -> Unit, onConfigure: () -> Unit) {
    val issue = s.notificationCaptureEnabled && !connected || s.captureMinshengSms && !smsGranted
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (!s.captureEnabled) "采集未开启" else if (issue) "采集权限待检查" else "自动采集中", s.captureEnabled && !issue)
            TextButton(onClick = onConfigure, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                Text("管理入口", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(count.toString(), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
                Text("已采集记录", style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
            }
            VerticalDivider(Modifier.height(42.dp).padding(end = 24.dp), color = Paper.Line)
            Column(Modifier.weight(1f)) {
                Text(pending.toString(), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
                Text("待投递记录", style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Paper.Line)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(painterResource(R.drawable.ic_delivery), null, Modifier.size(18.dp), tint = Paper.Muted)
            Column(Modifier.weight(1f)) {
                Text(if (s.autoSend) "每天 ${s.sendTime} 自动寄出" else "每日邮件未开启", style = MaterialTheme.typography.labelLarge)
                Text(if (s.autoSend) "前一日记录 · ${s.zone}" else "配置邮箱后开启", style = MaterialTheme.typography.labelSmall, color = Paper.Muted)
            }
            FilledTonalButton(onClick = onSend, enabled = !busy && mailReady && pending > 0,
                shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("寄出", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable private fun RecordCard(row: Observation) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    val bank = row.source == Minsheng.APP || row.source == Minsheng.SMS
    val source = when (row.source) {
        "com.tencent.mm" -> "微信支付"
        "com.eg.android.AlipayGphone" -> "支付宝"
        Minsheng.APP -> "民生借记卡"
        Minsheng.SMS -> "民生信用卡"
        else -> "其他来源"
    }
    val kind = when (row.kind) {
        "authorization" -> "预授权／消费待确认"
        "reversal" -> "消费冲正"
        "transfer" -> "转账／还款"
        "refund" -> "退款"
        "outflow" -> "资金流出"
        "inflow" -> "资金流入"
        "expense" -> "消费提示"
        "income" -> "收入提示"
        else -> "类型待核对"
    }
    Card(onClick = { expanded = !expanded }, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Paper.White), border = BorderStroke(1.dp, Paper.Line)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceMark(if (row.source == Minsheng.SMS) R.drawable.pw_credit else if (bank) R.drawable.pw_debit else R.drawable.pw_brand)
                Column(Modifier.weight(1f)) {
                    Text(source, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatTime(row.capturedAt)} · $kind", style = MaterialTheme.typography.labelSmall, color = Paper.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(amountText(row.amountMinor) ?: "待核对", fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp)
                    Text(row.currency ?: "币种未知", style = MaterialTheme.typography.labelSmall, color = Paper.Muted)
                }
            }
            if (row.review == "needs_review" || row.possibleDuplicateOf != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.review == "needs_review") SmallLabel("待核对", warning = true)
                if (row.possibleDuplicateOf != null) SmallLabel("可能重复", warning = true)
            }
            if (expanded) {
                HorizontalDivider(color = Paper.Line)
                Text("${row.memberName} · ${row.title}", style = MaterialTheme.typography.labelMedium, color = Paper.Muted)
                SelectionContainer { Text(row.text, style = MaterialTheme.typography.bodySmall) }
                Text("保留原始消息，金额与用途以实际账单为准。", style = MaterialTheme.typography.labelSmall, color = Paper.Muted)
            }
        }
    }
}
private fun formatTime(time: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun kindLabel(kind: String) = when (kind) { "TEST" -> "测试邮件"; "DAILY" -> "每日邮件"; else -> "手动发送" }
private fun statusLabel(status: String) = when (status) {
    "pending" -> "等待发送"; "retry" -> "等待重试"; "sending" -> "发送中"; "accepted" -> "服务器已接受"
    "uncertain" -> "结果不确定"; else -> "需要处理"
}
