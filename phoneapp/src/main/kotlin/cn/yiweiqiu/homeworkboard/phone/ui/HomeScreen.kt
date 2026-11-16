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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
    onSetItemsValidUntil: (Set<String>, String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<HomeworkItem?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    var editingUntil by remember { mutableStateOf(false) }
    // 批量：进入后点条目是「勾选」而不是「编辑」，勾完统一设一次期限
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var batchUntil by remember { mutableStateOf(false) }
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
                batchMode = batchMode,
                onToggleBatch = {
                    batchMode = !batchMode
                    if (!batchMode) selectedIds = emptySet()
                },
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
                            val key = item.id.ifBlank { item.hashCode().toString() }
                            ItemRow(
                                item = item,
                                selectable = batchMode,
                                selected = key in selectedIds,
                                onClick = {
                                    if (batchMode) {
                                        selectedIds = if (key in selectedIds) {
                                            selectedIds - key
                                        } else {
                                            selectedIds + key
                                        }
                                    } else {
                                        onEdit(item)
                                    }
                                },
                                onLongClick = { if (!batchMode) pendingDelete = item },
                            )
                        }
                    }
                }
            }

            // 整天共用的备注，和网页端的 notes 同一个字段，电视端显示成「提醒」
            if (batchMode) {
                BatchBar(
                    count = selectedIds.size,
                    onSetUntil = { if (selectedIds.isNotEmpty()) batchUntil = true },
                    onCancel = {
                        batchMode = false
                        selectedIds = emptySet()
                    },
                )
            } else {
                NotesBar(
                    notes = state.notes,
                    onClick = {
                        notesDraft = state.notes
                        editingNotes = true
                    },
                )
            }
        }

        if (!batchMode) FloatingActionButton(
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

    if (batchUntil) {
        UntilDialog(
            activeDate = state.activeDate,
            current = "",
            allowFollow = true,
            title = "这几条显示到哪天？",
            onDismiss = { batchUntil = false },
            onPick = {
                onSetItemsValidUntil(selectedIds, it)
                batchUntil = false
                batchMode = false
                selectedIds = emptySet()
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
 * 默认规则已经覆盖了周末（周五、周六布置的自动管到周日），这个对话框为没有规律的日子准备：
 * 国庆、春节、调休。
 *
 * 上面三档是快捷方式（只今天 / 到这周日 / 再连休 7 天），点一下就走；
 * 假期长度没有规律，光靠三档盖不住——所以最后一行给日历，能挑到任意一天。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UntilDialog(
    activeDate: String,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /**
     * 是否给一行「跟随当天」。
     *
     * 首页那个入口改的是整天，本来就是显式值，「跟随默认规则」没有意义；
     * 记作业页改的是单条，绝大多数条根本不该单独设期限，必须留一条回去的路。
     */
    allowFollow: Boolean = false,
    title: String = "电视端显示到哪天？",
) {
    if (activeDate.isBlank()) return
    val sunday = DayRule.nextSunday(activeDate)
    val week = DayRule.addDays(activeDate, 7)
    val options = buildList {
        add("只今天" to activeDate)
        if (sunday != activeDate) add("到这周日" to sunday)
        if (week != sunday) add("再连休 7 天" to week)
    }
    // 当前值不在快捷档里，说明是日历上自己挑的，要让它也有个「已选中」的样子
    val customDate = current.takeIf { c -> c.isNotBlank() && options.none { it.second == c } }

    var showPicker by remember { mutableStateOf(false) }
    val minMillis = isoToUtcMillis(activeDate) ?: 0L
    // 上限给一年：再长就不该靠「有效期」表达，八成是记错日子了
    val maxMillis = isoToUtcMillis(DayRule.addDays(activeDate, 365)) ?: Long.MAX_VALUE
    val minYear = runCatching { LocalDate.parse(activeDate).year }.getOrDefault(2000)
    val maxYear = runCatching { LocalDate.parse(DayRule.addDays(activeDate, 365)).year }
        .getOrDefault(minYear + 1)


    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = if (allowFollow) {
                        "不单独设置就跟当天走：今天布置的只管今天，周五、周六布置的自动管到周日。"
                    } else {
                        "默认规则：周五、周六布置的作业会自动管到周日。" +
                            "放假、调休没有规律，在这里手动往后延。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = phoneMuted,
                )
                Spacer(Modifier.height(12.dp))

                if (allowFollow) {
                    val following = current.isBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (following) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onPick("") }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "跟随当天",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (following) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = shortDate(DayRule.validUntilOf(activeDate, "")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = phoneMuted,
                        )
                    }
                }

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

                // 快捷档盖不住「放半个月」这种长度，给一个能翻日历的出口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (customDate != null) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { showPicker = true }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "自定义日期…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (customDate != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = customDate?.let { shortDate(it) } ?: "翻日历",
                        style = MaterialTheme.typography.bodyMedium,
                        color = phoneMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = isoToUtcMillis(current) ?: minMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis in minMillis..maxMillis

                override fun isSelectableYear(year: Int): Boolean =
                    year in minYear..maxYear
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let { onPick(utcMillisToIso(it)) }
                        showPicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(
                state = pickerState,
                // 电视端只认日期，不认时刻，留着切换按钮只会让人以为要选时间
                showModeToggle = false,
            )
        }
    }
}

/** `yyyy-MM-dd` → 日期选择器要的「当天 0 点（UTC）」毫秒数 */
private fun isoToUtcMillis(date: String): Long? = runCatching {
    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

/** 日期选择器给的毫秒数 → `yyyy-MM-dd`，按 UTC 还回去，跟 [isoToUtcMillis] 对称 */
private fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

/**
 * 批量模式的底部工具条。
 *
 * 放在原来「提醒」的位置：批量是一次性动作，没选完之前不需要看提醒；
 * 换掉它比叠一层浮条更干净，也不会盖住列表最后一条。
 */
@Composable
private fun BatchBar(count: Int, onSetUntil: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "点条目勾选" else "已选 $count 条",
            style = MaterialTheme.typography.bodyMedium,
            color = if (count == 0) phoneMuted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSetUntil, enabled = count > 0) { Text("设置显示到") }
        TextButton(onClick = onCancel) { Text("取消") }
    }
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
private fun Header(
    state: PhoneState,
    onOpenSettings: () -> Unit,
    onEditUntil: () -> Unit,
    batchMode: Boolean,
    onToggleBatch: () -> Unit,
) {
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
        // 只有真有作业时才给「批量」：空列表进去勾无可勾
        if (state.items.isNotEmpty()) {
            TextButton(
                onClick = onToggleBatch,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = if (batchMode) "完成" else "批量",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
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
internal fun shortDate(date: String): String = DayKeys.label(date).substringBefore(' ')

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
private fun ItemRow(
    item: HomeworkItem,
    selectable: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val subject = item.subject.ifBlank { "其他" }
    val dot = Color(SubjectPalette.colorOf(subject))
    // 只有「单独设过」的才显示日期标签：全都挂一串日期，反而看不出哪条特殊
    val ownUntil = item.validUntil.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) "已勾选" else "未勾选",
                tint = if (selected) MaterialTheme.colorScheme.primary else phoneMuted,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box(Modifier.size(9.dp).background(dot, CircleShape))
        }
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
                if (ownUntil != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "到 ${shortDate(ownUntil)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = phoneMuted,
                    )
                }
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
