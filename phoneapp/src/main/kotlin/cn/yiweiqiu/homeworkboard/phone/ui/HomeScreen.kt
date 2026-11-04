package cn.yiweiqiu.homeworkboard.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yiweiqiu.homework.core.DayKeys
import cn.yiweiqiu.homework.core.DayRule
import cn.yiweiqiu.homework.core.HomeworkItem
import cn.yiweiqiu.homework.core.SubjectPalette
import cn.yiweiqiu.homeworkboard.phone.PhoneState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: PhoneState,
    onAdd: () -> Unit,
    onEdit: (HomeworkItem) -> Unit,
    onDelete: (HomeworkItem) -> Unit,
    onOpenSettings: () -> Unit,
    onRetryPending: () -> Unit,
    onRefresh: () -> Unit,
    onSaveNotes: (String) -> Unit,
    onSetValidUntil: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<HomeworkItem?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    var editingUntil by remember { mutableStateOf(false) }
    var notesDraft by remember { mutableStateOf("") }
    val notesFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 打开提醒对话框就把光标放进去并弹键盘，省一次点击
    LaunchedEffect(editingNotes) {
        if (editingNotes) {
            delay(250)
            runCatching { notesFocus.requestFocus() }
            keyboard?.show()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Header(
                state = state,
                onOpenSettings = onOpenSettings,
                onEditUntil = { editingUntil = true },
            )

            if (state.pending > 0) {
                PendingBanner(count = state.pending, onRetry = onRetryPending)
            }

            Box(Modifier.weight(1f)) {
                when {
                    !state.configured -> EmptyHint("还没连坚果云\n点右上角齿轮填账号")
                    state.loading && state.items.isEmpty() -> EmptyHint("正在读取今天…")
                    // 失败不能说成"还没布置"：家长会以为孩子今天真没作业
                    state.error != null && state.items.isEmpty() -> ErrorHint(state.error, onRetry = onRefresh)
                    state.items.isEmpty() -> EmptyHint("今天还没布置\n点右下角 ＋ 记一条")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.id.ifBlank { it.hashCode().toString() } }) { item ->
                            ItemRow(
                                item = item,
                                onClick = { onEdit(item) },
                                onLongClick = { pendingDelete = item },
                            )
                        }
                    }
                }
            }

            // 整天共用的备注，和网页端的 notes 同一个字段，电视端显示成「提醒」
            NotesBar(
                notes = state.notes,
                onClick = {
                    notesDraft = state.notes
                    editingNotes = true
                },
            )
        }

        FloatingActionButton(
            onClick = onAdd,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(64.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "记一条", modifier = Modifier.size(30.dp))
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条？") },
            text = { Text(target.title.ifBlank { "作业" }) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("算了") }
            },
        )
    }

    if (editingUntil) {
        UntilDialog(
            activeDate = state.activeDate,
            current = state.untilLabel,
            onDismiss = { editingUntil = false },
            onPick = {
                onSetValidUntil(it)
                editingUntil = false
            },
        )
    }

    if (editingNotes) {
        AlertDialog(
            onDismissRequest = { editingNotes = false },
            title = { Text("今日提醒") },
            text = {
                Column {
                    Text(
                        text = "显示在电视上作业列表的下方，适合放「明天带跳绳」这类整天都成立的事。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = phoneMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = notesDraft,
                        onValueChange = { notesDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .tapToFocus(notesFocus, keyboard)
                            .focusRequester(notesFocus)
                            .height(110.dp),
                        placeholder = { Text("比如：明天带跳绳、8 点前睡觉") },
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = phoneLine,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveNotes(notesDraft)
                    editingNotes = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingNotes = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 「这批作业管到哪天」。
 *
 * 默认规则已经覆盖了周末（周五、周六布置的自动管到周日），这个对话框只为没有规律的日子准备：
 * 国庆、春节、调休。所以只给三个够用的档，不摆日历——家长在假期里最不想做的事就是翻日历。
 */
@Composable
private fun UntilDialog(
    activeDate: String,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    if (activeDate.isBlank()) return
    val sunday = DayRule.nextSunday(activeDate)
    val week = DayRule.addDays(activeDate, 7)
    val options = buildList {
        add("只今天" to activeDate)
        if (sunday != activeDate) add("到这周日" to sunday)
        if (week != sunday) add("再连休 7 天" to week)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电视端显示到哪天？") },
        text = {
            Column {
                Text(
                    text = "默认规则：周五、周六布置的作业会自动管到周日。" +
                        "放假、调休没有规律，在这里手动往后延。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = phoneMuted,
                )
                Spacer(Modifier.height(12.dp))
                options.forEach { (label, date) ->
                    val picked = date == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (picked) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onPick(date) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = shortDate(date),
                            style = MaterialTheme.typography.bodyMedium,
                            color = phoneMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 首页常驻的一条「提醒」：有内容就显示，没有就显示成一句浅色提示。
 * 放在列表下面而不是塞进记作业页，是因为它是整天的事、不属于任何一条作业。
 */
@Composable
private fun NotesBar(notes: String, onClick: () -> Unit) {
    val has = notes.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 右侧留出悬浮 ＋ 按钮的位置，否则「写」字被圆按钮压住
            .padding(start = 16.dp, end = 96.dp, top = 4.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (has) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .border(1.dp, phoneLine, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "提醒",
            style = MaterialTheme.typography.labelMedium,
            color = phoneMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (has) notes else "加一条今天的提醒（可选）",
            style = MaterialTheme.typography.bodyMedium,
            color = if (has) MaterialTheme.colorScheme.onSurface else phoneMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (has) "改" else "写",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Header(state: PhoneState, onOpenSettings: () -> Unit, onEditUntil: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.dateLabel.ifBlank { "今天" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.items.isEmpty()) "还没布置" else "已布置 ${state.items.size} 项",
                    style = MaterialTheme.typography.bodyMedium,
                    color = phoneMuted,
                )
                // 周末/假期看的是上一次布置的：不说清楚，家长会以为今天真没作业
                if (state.carryNote.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    MetaChip(text = state.carryNote)
                }
                // 只在真有作业时给入口：空的一天没有「管到哪天」可设
                if (state.items.isNotEmpty() && state.untilLabel.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    MetaChip(
                        text = untilText(state),
                        onClick = onEditUntil,
                    )
                }
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = phoneMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 「11月1日 周日」→「11月1日」，小标签里省掉星期几 */
private fun shortDate(date: String): String = DayKeys.label(date).substringBefore(' ')

private fun untilText(state: PhoneState): String {
    val until = state.untilLabel
    if (until.isBlank()) return ""
    return if (until == state.activeDate) "只今天" else "管到 ${shortDate(until)}"
}

/** 标题下面那些小标签：沿用说明、有效期入口 */
@Composable
private fun MetaChip(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (onClick == null) phoneMuted else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (onClick == null) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                },
            )
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingBanner(count: Int, onRetry: () -> Unit) {
    // 底色和文字都走主题：WarnSoft / Warn 是浅色专用色，
    // 深色主题下那两条硬编码会变成一块刺眼的浅橙底 + 看不清的暗红字
    val tone = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(phoneErrorSoft)
            .combinedClickable(onClick = onRetry, onLongClick = onRetry)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "待发送 $count 条",
            style = MaterialTheme.typography.bodyMedium,
            color = tone,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "点这里重试",
            style = MaterialTheme.typography.bodyMedium,
            color = tone.copy(alpha = 0.78f),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: HomeworkItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val subject = item.subject.ifBlank { "其他" }
    val dot = Color(SubjectPalette.colorOf(subject))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(dot, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.ifBlank { "作业" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subject,
                    style = MaterialTheme.typography.labelMedium,
                    color = dot,
                )
            }
            if (item.detail.isNotBlank()) {
                Text(
                    text = item.detail.replace('\n', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    color = phoneMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.images.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = phoneMuted,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "${item.images.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = phoneMuted,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = phoneMuted,
        )
    }
}

@Composable
private fun ErrorHint(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "没能读到今天的作业",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
