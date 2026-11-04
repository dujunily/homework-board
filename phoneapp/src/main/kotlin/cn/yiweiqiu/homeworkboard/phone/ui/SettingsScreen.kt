package cn.yiweiqiu.homeworkboard.phone.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yiweiqiu.homework.core.sync.SyncPhase
import cn.yiweiqiu.homeworkboard.phone.PhoneState
import cn.yiweiqiu.homeworkboard.phone.TestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页。
 *
 * 版式对齐课时簿 / 动账：顶栏 → 分区标题 → 白卡片，卡片里用分隔线切行。
 * 相对原来那版，改掉的是四件事：
 *
 * 1. **账号配好之后收起来**，只留「邮箱 + 状态 + 修改」。四个输入框常年摊在页面上，
 *    既显得没配完，也让人每次进来都得重看一遍「应用密码不是登录密码」那段话。
 * 2. **状态说人话**。「已连接 · 上次成功 3 分钟前」比一个绿点有用；
 *    失败时把原因原样放进状态条，而不是只在首页闪一下 Snackbar。
 * 3. **地址收进高级设置**。家长不该改目录，改错了两边就是两份数据。
 * 4. **保存不再跳回首页**。存完能接着测连接、立刻点一次同步，不用再摸回来。
 */
@Composable
fun SettingsScreen(
    state: PhoneState,
    initial: List<String>,
    test: TestResult?,
    onBack: () -> Unit,
    onSaveAccount: (url: String, user: String, pass: String) -> Unit,
    onTest: (url: String, user: String, pass: String) -> Unit,
    onSaveChild: (String) -> Unit,
    onSyncNow: () -> Unit,
    configJson: () -> String,
) {
    var url by remember { mutableStateOf(initial.getOrElse(0) { "" }) }
    var user by remember { mutableStateOf(initial.getOrElse(1) { "" }) }
    var pass by remember { mutableStateOf(initial.getOrElse(2) { "" }) }
    var child by remember { mutableStateOf(initial.getOrElse(3) { "" }) }

    // 没配置过就展开；配好了先收起，点「修改」才展开
    var editingAccount by remember { mutableStateOf(!state.configured) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 认证失败就把账号区摊开：这一刻用户唯一想做的就是把密码改对
    LaunchedEffect(state.phase) {
        if (state.phase == SyncPhase.AUTH_FAILED) editingAccount = true
    }

    fun commitChild() {
        val name = child.trim()
        child = name
        keyboard?.hide()
        onSaveChild(name)
    }

    /**
     * 把配置送到电脑上。
     *
     * 优先**发文件**而不是发一段文本：到了电脑上要的是一个 config.json，
     * 文本粘进记事本再「另存为」十有八九存成 config.json.txt，名字对不上电视就不认。
     * 文件发过去，落盘就是对的。取不到文件时退回纯文本。
     */
    fun shareToPc() {
        val file = java.io.File(context.getExternalFilesDir(null), "config.json")
        val uri = if (file.isFile) {
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        } else null

        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            if (uri != null) {
                // 用 */* 而不是 application/json：后者能接的应用太少（模拟器上直接
                // 显示「没有应用可执行此操作」），微信/QQ 发文件走的是通配类型
                type = "*/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 少数老版本接收方只认 clipData 里的 uri，不认 EXTRA_STREAM
                clipData = android.content.ClipData.newRawUri("config.json", uri)
            } else {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, configJson())
            }
            putExtra(android.content.Intent.EXTRA_SUBJECT, "作业板配置")
        }
        runCatching {
            context.startActivity(android.content.Intent.createChooser(send, "发到电脑"))
        }.onFailure {
            // 连相册都调不起来的话，退回文本，至少能把账号密码发出去
            val textOnly = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, configJson())
            }
            runCatching {
                context.startActivity(android.content.Intent.createChooser(textOnly, "发到电脑"))
            }.onFailure {
                Toast.makeText(context, "没有能接收的应用，可以先用「复制」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SettingsTopBar(title = "设置", subtitle = "坚果云同步 · 孩子 · 电视端", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            /* ---------------------------- 坚果云同步 ---------------------------- */

            SectionHeader("坚果云同步", "数据存在你自己的坚果云里，没有中转服务器")

            AppCard {
                if (editingAccount) {
                    if (state.phase == SyncPhase.AUTH_FAILED) {
                        StatusBanner(
                            title = "认证失败",
                            text = "坚果云 WebDAV 必须用「应用密码」，不是登录密码。",
                            ok = false,
                        )
                        VGap(12)
                    }

                    SettingsField(
                        value = user,
                        onChange = { user = it },
                        label = "坚果云账号",
                        placeholder = "you@example.com",
                        keyboardType = KeyboardType.Email,
                    )
                    VGap(10)
                    SettingsField(
                        value = pass,
                        onChange = { pass = it },
                        label = "应用密码",
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        visual = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    tint = phoneMuted,
                                )
                            }
                        },
                        onDone = { keyboard?.hide() },
                    )
                    VGap(4)
                    TextButton(
                        onClick = { showHelp = true },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text("怎么获取应用密码？", style = MaterialTheme.typography.labelMedium)
                    }

                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (showAdvanced) "收起高级设置" else "高级设置（坚果云地址）",
                            style = MaterialTheme.typography.labelMedium,
                            color = phoneMuted,
                        )
                    }
                    if (showAdvanced) {
                        VGap(6)
                        SettingsField(
                            value = url,
                            onChange = { url = it },
                            label = "坚果云地址",
                            placeholder = "留空即用默认目录",
                            keyboardType = KeyboardType.Uri,
                            onDone = { keyboard?.hide() },
                        )
                        VGap(4)
                        HintText("留空就行，默认 https://dav.jianguoyun.com/dav/作业板/ 。目录不存在会自动建一个，两台设备保持一致即可。")
                    }

                    VGap(14)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                keyboard?.hide()
                                onSaveAccount(url, user, pass)
                                if (user.isNotBlank() && pass.isNotBlank()) editingAccount = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { ActionLabel("保存账号") }

                        OutlinedButton(
                            onClick = {
                                keyboard?.hide()
                                onTest(url, user, pass)
                            },
                            enabled = test?.loading != true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { ActionLabel(if (test?.loading == true) "测试中…" else "测试连接") }

                        if (state.configured) {
                            TextButton(
                                onClick = {
                                    // 取消要连输入框一起还原，否则下次点保存会把改了一半的邮箱写进去
                                    url = initial.getOrElse(0) { "" }
                                    user = initial.getOrElse(1) { "" }
                                    pass = initial.getOrElse(2) { "" }
                                    passwordVisible = false
                                    showAdvanced = false
                                    editingAccount = false
                                    keyboard?.hide()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { ActionLabel("取消") }
                        }
                    }

                    test?.let { result ->
                        VGap(10)
                        if (result.loading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                HGap(8)
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = phoneMuted,
                                )
                            }
                        } else {
                            StatusBanner(text = result.message, ok = result.ok)
                        }
                    }
                } else {
                    // 收起态：只摆邮箱和状态，一眼能看出通没通
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.CloudSync, MaterialTheme.colorScheme.primary)
                        HGap(12)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = user,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = accountSubtitle(state),
                                style = MaterialTheme.typography.bodySmall,
                                color = phoneMuted,
                            )
                        }
                        TextButton(
                            onClick = { editingAccount = true },
                            modifier = Modifier.heightIn(min = 44.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text("修改", style = MaterialTheme.typography.labelMedium) }
                    }

                    CardDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "立即同步",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "上次成功：${agoText(state.lastSuccessAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = phoneMuted,
                            )
                        }
                        OutlinedButton(
                            onClick = onSyncNow,
                            enabled = state.phase != SyncPhase.SYNCING,
                            modifier = Modifier.heightIn(min = 44.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            if (state.phase == SyncPhase.SYNCING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            HGap(6)
                            Text(
                                text = if (state.phase == SyncPhase.SYNCING) "同步中" else "同步",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    if (state.pending > 0) {
                        VGap(10)
                        StatusBanner(
                            text = "本机还有 ${state.pending} 条没传上去，网络恢复后会自动补发。",
                            ok = false,
                        )
                    }

                    // 顶栏那个状态说不清卡在哪一步；失败原因原样放这儿，
                    // 免得「同步失败」看着像是数据真的丢了
                    val error = state.error
                    if (error != null && !editingAccount) {
                        VGap(10)
                        StatusBanner(text = error, ok = false)
                    }
                }
            }

            /* ------------------------------ 孩子 ------------------------------ */

            // 只留标题，不写副标题：这一块本来就只有「改个称呼」一件事，
            // 再配一句解释 + 单独一行「保存」按钮，占掉的比内容本身还多。
            SectionHeader("孩子")

            AppCard(contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Person, phoneMuted)
                    HGap(12)
                    Box(Modifier.weight(1f)) {
                        SettingsField(
                            value = child,
                            onChange = { child = it },
                            label = "孩子名字",
                            placeholder = "留空则电视端不显示称呼",
                            imeAction = ImeAction.Done,
                            onDone = { commitChild() },
                        )
                    }
                    HGap(10)
                    Button(
                        onClick = { commitChild() },
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { ActionLabel("保存") }
                }
            }

            /* ----------------------------- 电视端 ----------------------------- */

            SectionHeader("电视端", "电视上没法用遥控器打字，配置从这台手机导出去")

            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Tv, phoneMuted)
                    HGap(12)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (state.tvConfigPath != null) "配置已生成" else "还没生成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = state.tvConfigPath ?: "填完账号之后自动落盘",
                            style = MaterialTheme.typography.bodySmall,
                            color = phoneMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                CardDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(configJson()))
                            Toast.makeText(context, "已复制，粘贴到 config.json 里", Toast.LENGTH_SHORT).show()
                        },
                        enabled = state.configured,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { ActionLabel("复制") }

                    OutlinedButton(
                        // 剪贴板只能在手机内部流转；要送到电脑，走系统分享（微信文件传输助手 / QQ / 邮件）
                        onClick = { shareToPc() },
                        enabled = state.configured,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { ActionLabel("发到电脑") }

                    OutlinedButton(
                        onClick = { showConfig = !showConfig },
                        enabled = state.configured,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { ActionLabel(if (showConfig) "收起" else "查看") }
                }

                if (showConfig) {
                    VGap(10)
                    // 能长按选中复制，也能整段拖选——之前是一块纯文本，看得见取不走
                    SelectionContainer {
                        Text(
                            text = configJson(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp),
                        )
                    }
                    VGap(6)
                    HintText("长按可以选中复制。里面有应用密码明文，别发到群里。")
                }

                VGap(10)
                HintText(
                    "只有账号和密码两项，地址不用填——电视端缺省会自己用默认目录，没有就建一个。" +
                        "推到电视的位置：/sdcard/Android/data/cn.yiweiqiu.homeworkboard/files/config.json"
                )
            }

            /* ------------------------------ 页脚 ------------------------------ */

            VGap(22)
            HintText(
                "内容写完后 8 秒自动上传；打开 App、回到前台都会立刻对一次账。" +
                    "离线时先存进待发队列，联网后自动补发。"
            )
            VGap(8)
            HintText("作业板 v${versionName()}")
            VGap(30)
        }
    }

    if (showHelp) {
        AppPasswordHelpDialog(
            onDismiss = { showHelp = false },
            onOpenWeb = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://help.jianguoyun.com/?p=2064"),
                        )
                    )
                }
                showHelp = false
            },
        )
    }
}

/* ------------------------------ 小件 ------------------------------ */

/** 收起态那一行下面的说明：说清「通没通、上次什么时候」，而不是只写「已配置」 */
private fun accountSubtitle(state: PhoneState): String = when {
    !state.configured -> "还没填账号"
    state.phase == SyncPhase.AUTH_FAILED -> "认证失败，账号或应用密码不对"
    state.phase == SyncPhase.SYNCING -> "正在同步…"
    state.phase == SyncPhase.OFFLINE -> "网络不通，显示的是上次拉到的内容"
    state.phase == SyncPhase.SERVER_ERROR -> "坚果云暂时没响应，稍后自动重试"
    state.phase == SyncPhase.SPACE_FULL -> "坚果云空间不足"
    state.phase == SyncPhase.CONFLICT -> "云端刚被别处改过，再同步一次就好"
    state.lastSuccessAt > 0 -> "已连接 · 上次成功 ${agoText(state.lastSuccessAt)}"
    else -> "已配置，还没完成第一次同步"
}

private fun agoText(ts: Long): String {
    if (ts <= 0L) return "还没成功过"
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** 统一的输入框：圆角 12、实心虚线取主题色，四个字段长得一样 */
@Composable
private fun SettingsField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = phoneMuted) }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = trailing,
        colors = settingsFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun settingsFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = phoneLine,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/** 应用密码的获取步骤：一步步写清楚，附一个官网帮助页入口 */
@Composable
private fun AppPasswordHelpDialog(
    onDismiss: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("获取坚果云「应用密码」") },
        text = {
            Column {
                Text(
                    "WebDAV 不能用登录密码，必须单独生成一个应用密码：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                VGap(8)
                listOf(
                    "1. 电脑浏览器登录 jianguoyun.com",
                    "2. 右上角账户信息 → 安全选项",
                    "3. 找到「第三方应用管理」→ 添加应用",
                    "4. 名称填「作业板」，生成密码",
                    "5. 把这串密码填到上面（不是登录密码）",
                ).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = phoneMuted)
                }
                VGap(10)
                HintText("这串密码只存在本机，不会出现在任何日志里。换手机要重新填一次。")
            }
        },
        confirmButton = { TextButton(onClick = onOpenWeb) { Text("打开帮助页") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 从 PackageManager 读版本号，不依赖 BuildConfig 开关（release 开了 R8） */
@Composable
private fun versionName(): String {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }
}
