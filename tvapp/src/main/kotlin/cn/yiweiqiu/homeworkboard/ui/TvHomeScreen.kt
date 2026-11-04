package cn.yiweiqiu.homeworkboard.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yiweiqiu.homework.core.DavSession
import cn.yiweiqiu.homework.core.HomeworkItem
import cn.yiweiqiu.homework.core.SubjectPalette
import cn.yiweiqiu.homeworkboard.HomeViewModel
import cn.yiweiqiu.homeworkboard.UiState
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 字号候选：从大往小试，能一屏放下就不缩。
 *
 * 上限定在 24sp：实测 26sp 时 4 条就吃掉 580px，第 5 条挤不进去；
 * 24sp 一屏能踏实放 5 条，3 米外看依然清楚。
 */
private val FONT_LEVELS = listOf(24f, 23f, 22f, 21f, 20f, 19f, 18f)
private const val MIN_FONT = 18f
private const val LINE_HEIGHT_RATIO = 1.42f
private const val ROW_GAP_DP = 5f
/** 实际行间距：List 的 spacedBy 与字号估算必须用同一个值，否则「算得下、其实溢出」 */
private const val ROW_SPACING_DP = ROW_GAP_DP * 1.6f
/** 每行上下内边距之和，同样必须进估算 */
private const val ROW_VPAD_DP = 6f * 2
private const val INNER_PAD_DP = 16f

/**
 * 估算出的高度乘这个系数再和可用高度比。
 *
 * 文字实际排版会被标点、英文混排、字距挤得比「字数 × 字号」更宽，
 * 纯按公式算出来的总高度总是偏乐观，最后一行就容易被切掉半截。
 */
private const val FIT_SAFETY = 1.12f

/**
 * 缩略图跟着字号缩放：小字号时不占多余高度，一行能多塞几条。
 *
 * 下限从 44dp 抬到 48dp、倍数从 2.0 抬到 2.3：
 * 44dp 时「3 张」这个角标放不下，「张」字会被裁掉只剩一个数字。
 */
/**
 * 缩略图**最大**宽度（高度 = 宽 × [THUMB_ASPECT]）。
 *
 * 方框原本是正方形，图片 Crop 满铺时看不出问题；改成 Fit 之后正方形就不成立了：
 * 作业照片基本都是竖版整页（A4 约 1:1.41），等比缩进正方形里宽度只剩 0.7 倍，
 * 在 3 米外就是一条竖线。方框跟着改成竖矩形，竖图才几乎铺满、看得出「是一页纸」。
 *
 * 倍数从 2.3 抬到 3.6：一行 4 行字的作业本来就有 136dp 高，右侧却只放了个 55dp 的小方块，
 * 大片面积空着。缩略图现在按「撑满这一行的文字高度」取尺寸（上限就是这个值），
 * 文字高时图跟着长大、占满右侧；文字只有一行时也有下限兜着，不会缩成指甲盖。
 */
private fun thumbMaxW(fontSize: Float) = (fontSize * 3.6f).coerceIn(72f, 96f)

/** 取 1.3 而不是 A4 的 1.41，是把行高换回一点——一屏能放几条是硬指标 */
private const val THUMB_ASPECT = 1.3f
private fun thumbMaxH(fontSize: Float) = thumbMaxW(fontSize) * THUMB_ASPECT

/* --------- 左侧三列宽度（都以「字号」为单位，跟着字号缩放） --------- */

/** 序号列：`1.`、`12.` 这类两三字符足矣，1.6 太宽，右边白白空一截 */
private const val INDEX_COL_FACTOR = 1.1f
/** 行尾科目小标签里的字相对正文的大小 */
private const val SUBJECT_FONT_FACTOR = 0.5f
/** 行尾科目标签的总宽（×字号）：最多两字 + 左右内边距，用于高度估算 */
private const val SUBJECT_END_FACTOR = 1.5f
/** 序号与正文之间、以及行尾元素之间的横向间距 */
private const val COL_GAP_DP = 7f
/** 每行左右内边距（单边），越小左边越不占地 */
private const val ROW_HPAD_DP = 8f

@Composable
fun TvHomeRoute(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TvHomeScreen(state = state, onRefresh = { viewModel.refresh(manual = true) })
}

@Composable
fun TvHomeScreen(state: UiState, onRefresh: () -> Unit) {
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    val refreshFocus = remember { FocusRequester() }

    // 打开时先不画焦点框：孩子一看见绿框就以为「必须先按点什么」。
    // 焦点其实已经在第一条上了，碰一下方向键/OK 就进入导航态，之后焦点框照常跟随。
    var navStarted by remember { mutableStateOf(false) }

    // 防误触：孩子乱按遥控器，第一下返回只提示，第二下才真退到桌面
    val context = LocalContext.current
    var exitArmed by remember { mutableStateOf(false) }
    BackHandler {
        if (exitArmed) (context as? Activity)?.finish() else exitArmed = true
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2500)
            exitArmed = false
        }
    }

    // 没有可聚焦的行时（空态/引导页），把焦点落到顶栏刷新键上：
    // 落在整屏根节点时方向键出不去，孩子想刷新会「怎么按都没反应」。
    LaunchedEffect(state.items.isEmpty()) {
        if (state.items.isEmpty()) {
            delay(150)
            runCatching { refreshFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Bg)
            // 有列表时不要抢焦点，否则方向键会落到根节点上
            .then(
                if (state.items.isEmpty()) {
                    Modifier.focusable()
                } else {
                    Modifier
                },
            )
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> {
                        onRefresh(); true
                    }
                    // 一旦开始用方向键，才把焦点框显出来
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft,
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter,
                    -> {
                        navStarted = true; false
                    }
                    else -> false
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gutter = maxWidth * 0.05f
            val vertical = maxHeight * 0.035f
            val headerGap = maxHeight * 0.02f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = gutter, vertical = vertical),
            ) {
                Header(state = state, onRefresh = onRefresh, refreshFocus = refreshFocus)
                Spacer(Modifier.height(headerGap))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        state.needConfig -> ConfigGuide(state)
                        state.loading && state.items.isEmpty() -> CenterText("正在读取作业…")
                        state.items.isEmpty() -> EmptyState(state)
                        else -> HomeworkList(
                            items = state.items,
                            notes = state.notes,
                            showFocus = navStarted,
                            onOpenImages = { index -> viewer = ViewerRequest(index) },
                        )
                    }
                }
            }
        }

        if (exitArmed) {
            Text(
                text = "再按一次返回键退出",
                fontSize = 18.sp,
                color = TvColors.Body,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .background(TvColors.Card, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        val request = viewer
        if (request != null) {
            val item = state.items.getOrNull(request.index)
            val images = item?.images.orEmpty()
            if (images.isEmpty()) {
                viewer = null
            } else {
                ImageViewer(images = images, onClose = { viewer = null })
            }
        }
    }
}

private data class ViewerRequest(val index: Int)

/* ------------------------------- 顶栏 ------------------------------- */

@Composable
private fun Header(state: UiState, onRefresh: () -> Unit, refreshFocus: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.dateLabel,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = TvColors.Ink,
        )
        // 周末和假期显示的是上一次布置的作业：标题下面的日期会「停」在周五，
        // 不说一句孩子会以为日历坏了。
        if (state.carryNote.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = state.carryNote,
                fontSize = 17.sp,
                color = TvColors.Body,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvColors.Card)
                    .border(BorderStroke(1.dp, TvColors.Border), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        if (state.items.isNotEmpty()) {
            Text(text = "${state.items.size} 项作业", fontSize = 18.sp, color = TvColors.Muted)
        }
        // 「按 OK 看图」只在这里说一次。逐条挂在作业后面会把正文挤成两行，
        // 而且每行都重复一遍，看着吵。做成带底色的标签，远看也能注意到。
        if (state.items.any { it.images.isNotEmpty() }) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = "按 OK 看图",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = TvColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvColors.CardFocused)
                    .border(BorderStroke(1.dp, TvColors.Accent), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(24.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 错误文案可能很长，让它先被压缩，别把刷新按钮挤出屏幕
                StatusBadge(state, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(18.dp))
                RefreshButton(busy = state.syncing, onClick = onRefresh, focusRequester = refreshFocus)
            }
        }
    }
}

/**
 * 顶栏刷新键：遥控器停在它上面按 OK 即可手动同步，不用记「菜单键」这种隐藏操作。
 * 同步中不可再点（避免连点叠请求），但保持可聚焦，免得焦点突然消失。
 */
@Composable
private fun RefreshButton(busy: Boolean, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        busy -> TvColors.Muted
        focused -> TvColors.Accent
        else -> TvColors.Body
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) TvColors.CardFocused else Color.Transparent)
            .then(
                if (focused) {
                    Modifier.border(BorderStroke(2.dp, TvColors.Accent), RoundedCornerShape(10.dp))
                } else {
                    Modifier.border(BorderStroke(1.dp, TvColors.Border), RoundedCornerShape(10.dp))
                },
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                val isClick = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (event.type == KeyEventType.KeyDown && isClick) {
                    if (!busy) onClick()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = if (busy) "◌" else "↻", fontSize = 20.sp, color = tint)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (busy) "同步中" else "刷新",
            fontSize = 17.sp,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusBadge(state: UiState, modifier: Modifier = Modifier) {
    val color: Color
    val label: String
    when {
        state.syncing -> {
            color = TvColors.Muted; label = "正在同步…"
        }

        !state.error.isNullOrBlank() -> {
            color = TvColors.Warn
            label = if (state.updatedLabel.isBlank()) state.error else "${state.error} · 数据为 ${state.updatedLabel}"
        }

        state.offline -> {
            color = TvColors.Warn
            label = "离线 · 数据为 ${state.updatedLabel.ifBlank { "--:--" }}"
        }

        else -> {
            color = TvColors.Accent
            label = "更新于 ${state.updatedLabel.ifBlank { "--:--" }}"
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 18.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/* ------------------------------ 作业列表 ------------------------------ */

/** 标题与详情之间的连接符：标题已带句末标点时用空格，否则用冒号 */
private fun titleSeparator(title: String, detail: String): String {
    if (detail.isBlank()) return ""
    val last = title.trim().lastOrNull()
    return if (last != null && last in "。！？!?；;，,") " " else "："
}

private fun joinTitleDetail(title: String, detail: String): String =
    if (detail.isBlank()) title else title + titleSeparator(title, detail) + detail

private fun lineText(item: HomeworkItem): String =
    joinTitleDetail(item.title.ifBlank { "作业" }, item.detail)

private fun linesOf(text: String, charsPerLine: Int): Int {
    if (text.isBlank()) return 0
    return text.split('\n').sumOf { seg ->
        if (seg.isBlank()) 1 else ceil(seg.length.toFloat() / charsPerLine).toInt()
    }
}

/**
 * 一行能放多少字。
 *
 * 有图和没图的条目可用宽度差一格缩略图+间距（约 4 个字），必须分开算：
 * 之前对所有条目统一扣掉缩略图宽度，没图的条目被算窄了、行数偏多，
 * 字号选择和垂直居中跟着偏保守。缩略图放大到 3.6 倍之后这个偏差更明显，所以拆开。
 */
private fun charsPerLineFor(widthDp: Float, fontSize: Float, withThumb: Boolean): Int {
    // 行内固定占位：序号列 + 行尾科目标签 + 左右 padding + 间距；有图的再加缩略图那一格
    // 布局改了要同步这里，否则「算得下、其实溢出」
    val fixed = fontSize * (INDEX_COL_FACTOR + SUBJECT_END_FACTOR) +
        COL_GAP_DP * 2 + ROW_HPAD_DP * 2 +
        if (withThumb) thumbMaxW(fontSize) + 12f else 0f
    return ((widthDp - INNER_PAD_DP * 2 - fixed) / fontSize).toInt().coerceAtLeast(6)
}

/** 内容总高度估算。字号选择与垂直居中都用它，保证两边口径一致 */
private fun contentHeight(
    items: List<HomeworkItem>,
    notes: String,
    widthDp: Float,
    fontSize: Float,
): Float {
    val cplPlain = charsPerLineFor(widthDp, fontSize, withThumb = false)
    val cplThumb = charsPerLineFor(widthDp, fontSize, withThumb = true)
    var sum = if (notes.isBlank()) 0f else {
        linesOf(notes, cplPlain) * fontSize * LINE_HEIGHT_RATIO + ROW_GAP_DP * 2
    }
    items.forEach { item ->
        val cpl = if (item.images.isEmpty()) cplPlain else cplThumb
        val textH = linesOf(lineText(item), cpl) * fontSize * LINE_HEIGHT_RATIO
        // 有图的条目按「最大缩略图」估，宁可算多不算少
        val rowH = maxOf(textH, if (item.images.isEmpty()) 0f else thumbMaxH(fontSize))
        sum += rowH + ROW_VPAD_DP + ROW_SPACING_DP
    }
    return sum
}

/** 能用大字号一屏放下就用大的，放不下就取最小字号交给滚动 */
private fun fitFontSize(items: List<HomeworkItem>, notes: String, widthDp: Float, heightDp: Float): Float {
    val usable = (heightDp - INNER_PAD_DP * 2).coerceAtLeast(80f)
    for (level in FONT_LEVELS) {
        if (contentHeight(items, notes, widthDp, level) * FIT_SAFETY <= usable) return level
    }
    return MIN_FONT
}

@Composable
private fun HomeworkList(
    items: List<HomeworkItem>,
    notes: String,
    showFocus: Boolean,
    onOpenImages: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontSize = remember(items, notes, maxWidth, maxHeight) {
            fitFontSize(items, notes, maxWidth.value, maxHeight.value)
        }

        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
        // 下面还有内容时，底边渐隐，别让半截行硬生生被切一刀
        val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }

        // 进场把焦点落到第一条
        LaunchedEffect(items) {
            delay(250)
            runCatching { requesters.firstOrNull()?.requestFocus() }
        }

        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TvColors.Card),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = INNER_PAD_DP.dp,
                        vertical = INNER_PAD_DP.dp,
                    ),
                    // 条数少、内容撑不满一屏时整块清单垂直居中（留白上下对半分）。
                    // 用 LazyColumn 自带的 alignment 而不是手算 padding：
                    // 高度估算只服务字号选择，拿它算留白必然有偏差（实测偏了 60dp）；
                    // 内容超出时 alignment 自动失效，照常从顶部开始滚动。
                    verticalArrangement = Arrangement.spacedBy(
                        ROW_SPACING_DP.dp,
                        Alignment.CenterVertically,
                    ),
                ) {
                    itemsIndexed(items, key = { _, it -> it.id.ifBlank { it.hashCode().toString() } }) { index, item ->
                        HomeworkRow(
                            index = index + 1,
                            item = item,
                            fontSize = fontSize,
                            showFocus = showFocus,
                            focusRequester = requesters.getOrNull(index),
                            onFocused = {
                                // 只把「看不见的条目」瞬间滚进来：用动画会在连按时吞掉后续焦点移动
                                val info = listState.layoutInfo
                                val target = info.visibleItemsInfo.firstOrNull { it.index == index }
                                val fullyVisible = target != null &&
                                    target.offset >= info.viewportStartOffset &&
                                    target.offset + target.size <= info.viewportEndOffset
                                if (!fullyVisible) {
                                    scope.launch { listState.scrollToItem(index) }
                                }
                            },
                            onOpenImages = { onOpenImages(index) },
                        )
                    }
                }

                if (canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(26.dp)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, TvColors.Card)),
                            ),
                    )
                }
            }

            if (notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TvColors.NoteBg)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "提醒",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvColors.Warn,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = notes,
                        fontSize = 20.sp,
                        color = TvColors.NoteInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeworkRow(
    index: Int,
    item: HomeworkItem,
    fontSize: Float,
    showFocus: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onOpenImages: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // 已经聚焦但用户还没开始操作时，不画框：这一帧看起来应该是「一张干净的清单」
    val ring = focused && showFocus
    val subject = item.subject.ifBlank { "其他" }
    // 科目色（SubjectPalette）是给浅底调的：#C05A4A 这种低饱和深红压在 #1E232B 上
    // 对比度只有 3.4:1，三米外就是一团发闷的暗块，绿底标签也认不出是语文还是数学。
    // 深色下往白里调 32%——色相保留（红仍是语文、蓝仍是数学），亮度才够上电视。
    // 只在电视端做这个补偿，手机端浅色界面继续用原色。
    val dark = isSystemInDarkTheme()
    val subjectColor = Color(SubjectPalette.colorOf(subject))
        .let { if (dark) lerp(it, Color.White, 0.32f) else it }

    // 左边只留序号一列，科目挪到行尾。
    //
    // 之前科目占左边一列（先写死 2.7 倍字号，后改成按最长科目自适应），
    // 无论怎么写都在正文前面横着一块，正文被推右、长作业被挤成更多行。
    // 现在左边只剩「1.」这一个窄列，正文从接近卡片左边缘处开始，
    // 科目以科目色小标签贴在行尾——扫视时也能靠右侧那一条竖列分科。
    val indexCol = (fontSize * INDEX_COL_FACTOR).dp

    // 缩略图尺寸：固定成 thumbMaxW × thumbMaxH，不跟着文字行数缩放。
    //
    // 试过「撑满这一行文字的高度」：字多的条目（4 行=136dp）确实好看，
    // 但字少的条目（一行=34dp）图就缩成 48dp 的小方块，右侧反而空出一大片，
    // 三条作业摆在一起一大一小，像没对齐。固定尺寸之后右侧是一条整齐的图片列，
    // 扫一眼就知道哪几条带图；行高由「图片高度」兜底，一屏仍然放得下。
    val thumbSize: Pair<Float, Float>? = if (item.images.isEmpty()) {
        null
    } else {
        thumbMaxW(fontSize) to thumbMaxH(fontSize)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (ring) TvColors.CardFocused else Color.Transparent)
            .then(
                if (ring) {
                    Modifier.border(
                        BorderStroke(2.dp, TvColors.Accent),
                        RoundedCornerShape(10.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                val isClick = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (event.type == KeyEventType.KeyDown && isClick && item.images.isNotEmpty()) {
                    onOpenImages(); true
                } else {
                    false
                }
            }
            .padding(horizontal = ROW_HPAD_DP.dp, vertical = (ROW_VPAD_DP / 2).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index.",
            modifier = Modifier.width(indexCol),
            fontSize = fontSize.sp,
            lineHeight = (fontSize * LINE_HEIGHT_RATIO).sp,
            fontWeight = FontWeight.Bold,
            color = if (ring) TvColors.Ink else TvColors.Muted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )

        Spacer(Modifier.width(COL_GAP_DP.dp))

        Text(
            text = buildAnnotatedString {
                val title = item.title.ifBlank { "作业" }
                val sep = titleSeparator(title, item.detail)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TvColors.Ink)) {
                    append(title)
                }
                if (item.detail.isNotBlank()) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = TvColors.Body)) {
                        append(sep)
                        append(item.detail)
                    }
                }
            },
            modifier = Modifier.weight(1f),
            fontSize = fontSize.sp,
            lineHeight = (fontSize * LINE_HEIGHT_RATIO).sp,
            color = TvColors.Ink,
        )

        if (thumbSize != null) {
            Spacer(Modifier.width(12.dp))
            Thumbnail(item = item, wDp = thumbSize.first, hDp = thumbSize.second)
        }

        Spacer(Modifier.width(COL_GAP_DP.dp))

        // 行尾科目：只占它自己那点宽度，而且做得比正文小一档。
        // 底色淡到几乎看不见，是为了让它「有形状、能扫视」，但不抢正文。
        // 顶对齐首行：作业有好几行时，居中的标签会悬在中间显得没着落。
        Text(
            text = subjectShort(subject),
            modifier = Modifier
                .align(Alignment.Top)
                .padding(top = (fontSize * 0.16f).dp)
                .clip(RoundedCornerShape((fontSize * 0.42f).dp))
                // alpha 0.1 在浅色下是「几乎看不见的一层底」，深色下这个透明度等于没有底，
                // 标签变成纯文字浮在行尾，扫视时少了一个抓手，所以深色给到 0.2。
                .background(subjectColor.copy(alpha = if (dark) 0.20f else 0.10f))
                .padding(
                    horizontal = (fontSize * 0.22f).dp,
                    vertical = (fontSize * 0.05f).dp,
                ),
            fontSize = (fontSize * SUBJECT_FONT_FACTOR).sp,
            lineHeight = (fontSize * 0.72f).sp,
            fontWeight = FontWeight.Medium,
            color = subjectColor,
            maxLines = 1,
        )
    }
}

/**
 * 科目简称：行尾空间窄，长科目名要收成两个字。
 *
 * 「道德与法治」「道德与法律」统一显示「道法」，其余常见长名同理；
 * 没收录的按前两个字截（小学科目两字占绝大多数，截完正好齐整）。
 */
private fun subjectShort(subject: String): String {
    val s = subject.trim()
    if (s.isEmpty()) return "其他"
    SUBJECT_ALIASES[s]?.let { return it }
    return if (s.length <= 2) s else s.take(2)
}

private val SUBJECT_ALIASES = mapOf(
    "道德与法治" to "道法",
    "道德与法律" to "道法",
    "道德" to "道法",
    "思想品德" to "道法",
    "品德" to "道法",
    "综合实践" to "综合",
    "综合实践活动" to "综合",
    "信息技术" to "信息",
    "信息科技" to "信息",
    "体育与健康" to "体育",
    "劳动教育" to "劳动",
    "劳动技术" to "劳动",
    "心理健康" to "心理",
    "科学实验" to "科学",
)

/**
 * 缩略图。多图时做两件事：
 * 1. 主图往左上缩一点、右下露出一层浅色边 —— 像一叠照片，余光扫过就知道不止一张；
 * 2. 右下角压一个「N 张」角标，把张数说死。
 *
 * 角标原来按 sizeDp×0.19 取字号（最小 11sp）。那时图片是 Crop 满铺，角标压得住；
 * 改成 Fit 之后竖版作业在方框里只剩中间一条，11sp 的角标直接把图盖没了。
 * 现在压到 9~11sp、内边距也收窄——它只是「几张」这个次要信息的提示，
 * 主要的「有图/多图」靠图片本身和叠层表达，看不清角标也不影响看懂。
 */
@Composable
private fun Thumbnail(item: HomeworkItem, wDp: Float, hDp: Float) {
    val url = DavSession.absoluteUrl(item.images.first())
    val count = item.images.size
    val multi = count > 1
    val stackOffset = if (multi) (wDp * 0.09f).coerceIn(3f, 6f) else 0f

    Box(modifier = Modifier.width(wDp.dp).height(hDp.dp)) {
        // 底下那几张：错位露出一角
        if (multi) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = stackOffset.dp, top = stackOffset.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvColors.Border),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = stackOffset.dp, bottom = stackOffset.dp)
                .clip(RoundedCornerShape(8.dp))
                // 底色必须跟着主题走，不能写死：原来是 0xFF0C1016 的近黑，
                // 图片 Crop 满铺时看不见；改成 Contain 之后四周全是留白，
                // 黑框会变成白卡片上最扎眼的东西，于是换成比卡片深一档的米灰。
                // 反过来深色主题下米灰又变成深底上最亮的一块，所以交给 TvColors.ThumbBg，
                // 浅色 = 比卡片深一档，深色 = 比卡片亮一档，两边都是「纸落在桌面上」。
                .background(TvColors.ThumbBg)
                // 描边是深色主题的必需品，不是装饰：作业照片里很大一部分是**手机截图**，
                // 而截图本身往往就是深色界面，跟 ThumbBg 只差几个色阶，不描边就整块糊进卡片，
                // 看不出「这里有一张图」。浅色下这圈描边正好给白纸压个边界，也不难看。
                .border(1.dp, TvColors.Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    // Fit 而不是 Crop：作业照片几乎都是竖版整页练习，
                    // 裁成方块后只剩中间一条，等于只剩「有图」这个信号、看不出是什么。
                    // 等比缩进方框里，长边贴边、短边留白，一眼能认出「这是一整页」。
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                )
            }
            if (multi) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        // 角标一直是「半透明黑底 + 白字」，浅色下够用；
                        // 深色下图片本身常是深色截图，黑底黑图只剩白字浮着，看不出是个角标。
                        // 加一圈半透明白描边，在亮图和暗图上都能立住。
                        .background(Color(0xB3000000))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "$count 张",
                        fontSize = (wDp * 0.15f).coerceIn(9f, 11f).sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/* ------------------------------ 全屏看图 ------------------------------ */

@Composable
private fun ImageViewer(images: List<String>, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    BackHandler { onClose() }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        index = (index - 1 + images.size) % images.size; true
                    }

                    Key.DirectionRight -> {
                        index = (index + 1) % images.size; true
                    }

                    Key.DirectionCenter, Key.Enter, Key.Back, Key.Escape -> {
                        onClose(); true
                    }

                    else -> false
                }
            },
    ) {
        val url = DavSession.absoluteUrl(images[index])
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            )
        } else {
            Text(
                text = "图片地址不可用（未配置账号）",
                color = TvColors.Muted,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xD9000000))
                .padding(horizontal = 24.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1} / ${images.size}",
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Spacer(Modifier.width(22.dp))
            // 写清楚「怎么退」，别让人对着全屏图片猜：返回键和 OK 都能退
            Text(
                text = "← → 切换     返回键 / OK 退出",
                fontSize = 21.sp,
                color = Color(0xFFF0F2F5),
            )
        }
    }
}

/* ------------------------------- 空态 / 引导 ------------------------------- */

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 30.sp, color = TvColors.Muted)
    }
}

@Composable
private fun EmptyState(state: UiState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "今天还没有布置作业",
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
            color = TvColors.Ink,
        )
        if (state.latestLabel.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text(text = "最近一次：${state.latestLabel}", fontSize = 20.sp, color = TvColors.Muted)
        }
        if (!state.error.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(text = state.error, fontSize = 18.sp, color = TvColors.Warn)
        }
    }
}

@Composable
private fun ConfigGuide(state: UiState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有配置坚果云账号",
            fontSize = 38.sp,
            fontWeight = FontWeight.SemiBold,
            color = TvColors.Ink,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "把 config.json 放到下面这个位置，然后重新打开应用：",
            fontSize = 20.sp,
            color = TvColors.Body,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "文件里只要两行：坚果云账号和应用密码。地址不用写，云端目录会自动建。",
            fontSize = 17.sp,
            color = TvColors.Muted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.configPath,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = TvColors.Accent,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "adb push config.json ${state.configPath}",
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = TvColors.Muted,
        )
        Spacer(Modifier.height(20.dp))
        Text(text = "包名：${state.packageName}", fontSize = 16.sp, color = TvColors.Muted)
        if (!state.error.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(text = state.error, fontSize = 18.sp, color = TvColors.Warn)
        }
    }
}
