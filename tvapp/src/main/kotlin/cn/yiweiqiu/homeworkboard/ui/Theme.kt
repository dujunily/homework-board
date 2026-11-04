package cn.yiweiqiu.homeworkboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 大屏配色：跟随系统深浅主题（和手机端同一套做法）。
 *
 * 电视上的实际情况要说清楚：Android TV 系统把 uiMode 锁在 night=yes
 * （`dumpsys uimode` → `mNightMode=2(yes)`、`mNightModeLocked=true`，电视设置里没有深色开关），
 * 所以「跟随系统」在电视上等于**固定深色**。写成跟随仍然值得：
 * 个别 TV 盒子 / 平板装 TV 版时系统是浅色，那时才会走浅色那套，不必为不同设备发两个包。
 *
 * 浅色那套保留原样（米白底 + 深墨字），深色那套不是简单反相：
 * 电视屏大、客厅偏暗，纯黑底会把画面衬得像块洞，所以底色用带一点蓝的 #12151A，
 * 卡片再亮一档形成层次；强调色必须提亮（#2E9E63 压深底上会发闷、看不清边框）。
 */
private object TvLight {
    val Bg = Color(0xFFF5F4EF)
    val Card = Color(0xFFFFFFFF)
    val CardFocused = Color(0xFFEDF7F0)
    val Border = Color(0xFFE1E0D8)
    val Ink = Color(0xFF1E2328)
    val Body = Color(0xFF4B525A)
    val Muted = Color(0xFF8A939F)
    val Accent = Color(0xFF2E9E63)
    val Warn = Color(0xFFB26A00)
    val NoteBg = Color(0xFFFFF6E0)
    val NoteInk = Color(0xFF7A5A12)
    val ThumbBg = Color(0xFFEBEAE3)
}

private object TvDark {
    val Bg = Color(0xFF12151A)
    // 卡片比底色高 27 级而不是照着浅色版「差 10 级」来：浅色版靠纯白本身就能浮起来，
    // 深色版两块暗灰差得太近，三米外看电视就是一片，看不出作业还在一张卡片上。
    val Card = Color(0xFF242932)
    // 焦点底色不能比卡片暗：浅色版是「白 → 淡绿」变亮，深色版若用暗绿就成了「按下变暗」，
    // 遥控器上会觉得选中状态是灰掉了。做成「同亮度、带绿味」，再靠外面那圈 2dp 绿框说话。
    val CardFocused = Color(0xFF26332B)
    val Border = Color(0xFF333A43)
    val Ink = Color(0xFFF2F1ED)
    val Body = Color(0xFFC3C8CE)
    val Muted = Color(0xFF949DA6)
    val Accent = Color(0xFF57C88F)
    val Warn = Color(0xFFE0A65C)
    val NoteBg = Color(0xFF2A2318)
    val NoteInk = Color(0xFFE9CB8E)
    // 缩略图底：浅色下要「纸色比白卡片深一档」，深色下同理要「比卡片亮一档」，
    // 否则整页作业纸缩进来和白纸边缘糊在一起，看不出是一张纸
    val ThumbBg = Color(0xFF313844)
}

/*
 * 界面里只认 TvColors.*，深浅由 getter 当场决定。
 *
 * 用 @Composable getter（而不是启动时算一份常量）是因为系统主题可能在后台被改，
 * 重进前台时 Compose 会重新读 LocalConfiguration，颜色跟着换；
 * 写成常量就得自己监听 uiMode 广播，还得重启界面。
 *
 * 代价：这些字段**只能在 @Composable 里读**。要往非 composable 的地方传颜色，先取出来再传。
 */
object TvColors {
    val Bg: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Bg else TvLight.Bg
    val Card: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Card else TvLight.Card
    val CardFocused: Color @Composable get() =
        if (isSystemInDarkTheme()) TvDark.CardFocused else TvLight.CardFocused
    val Border: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Border else TvLight.Border
    val Ink: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Ink else TvLight.Ink
    val Body: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Body else TvLight.Body
    val Muted: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Muted else TvLight.Muted
    val Accent: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Accent else TvLight.Accent
    val Warn: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.Warn else TvLight.Warn
    val NoteBg: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.NoteBg else TvLight.NoteBg
    val NoteInk: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.NoteInk else TvLight.NoteInk
    val ThumbBg: Color @Composable get() = if (isSystemInDarkTheme()) TvDark.ThumbBg else TvLight.ThumbBg
}

private val LightScheme = lightColorScheme(
    primary = TvLight.Accent,
    onPrimary = Color.White,
    background = TvLight.Bg,
    onBackground = TvLight.Ink,
    surface = TvLight.Card,
    onSurface = TvLight.Ink,
    surfaceVariant = TvLight.CardFocused,
    onSurfaceVariant = TvLight.Body,
    outline = TvLight.Border,
    error = TvLight.Warn,
)

private val DarkScheme = darkColorScheme(
    primary = TvDark.Accent,
    // 深绿底上写白字会发灰，用近黑压住，和手机端深色主题同一个取向
    onPrimary = Color(0xFF08120C),
    background = TvDark.Bg,
    onBackground = TvDark.Ink,
    surface = TvDark.Card,
    onSurface = TvDark.Ink,
    surfaceVariant = TvDark.CardFocused,
    onSurfaceVariant = TvDark.Body,
    outline = TvDark.Border,
    error = TvDark.Warn,
)

@Composable
fun HomeworkBoardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
