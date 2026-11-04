package cn.yiweiqiu.homeworkboard.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.app.Activity

object PhoneColors {
    val Bg = Color(0xFFF7F4EE)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1E2328)
    val Body = Color(0xFF444B52)
    val Muted = Color(0xFF8A8375)
    val Line = Color(0xFFE7E1D6)
    val Accent = Color(0xFF2F6B4F)
    val AccentSoft = Color(0xFFE6EFE9)
    val Warn = Color(0xFFB4552F)
    val WarnSoft = Color(0xFFF8EAE2)

    // 深色下只用一套，避免半夜刺眼
    val DarkBg = Color(0xFF15181B)
    val DarkCard = Color(0xFF1E2227)
    val DarkInk = Color(0xFFF1F0EC)
    val DarkBody = Color(0xFFCFCCC4)
    val DarkLine = Color(0xFF2C3238)
    val DarkMuted = Color(0xFF9AA3AC)
}

/*
 * 跟随主题的前景色。
 *
 * 上面的 Ink/Muted/Line *只对浅色主题成立*：深色主题下背景是 0xFF15181B，
 * 再用 0xFF1E2328 的 Ink 画图标就是「黑底黑箭头」，屏幕上等于消失。
 * 所以界面里一律用下面这三个，不要让 PhoneColors.Ink 直接进 UI。
 */
val phoneInk: Color
    @Composable get() = if (isSystemInDarkTheme()) PhoneColors.DarkInk else PhoneColors.Ink

val phoneMuted: Color
    @Composable get() = if (isSystemInDarkTheme()) PhoneColors.DarkMuted else PhoneColors.Muted

val phoneLine: Color
    @Composable get() = if (isSystemInDarkTheme()) PhoneColors.DarkLine else PhoneColors.Line

/*
 * 状态条的底色。
 *
 * AccentSoft / WarnSoft 是给浅色背景配的：#E6EFE9 铺在深色卡片上就是一块发光的浅绿，
 * #F8EAE2 同理。深色下必须换成暗底，否则「提醒条」比正文还刺眼。
 * 文字色不在这里定：成功用 colorScheme.primary、失败用 colorScheme.error，
 * 这两个主题里都已经各自调过明度。
 */
val phoneSuccessSoft: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1B2A22) else PhoneColors.AccentSoft

val phoneErrorSoft: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF32211B) else PhoneColors.WarnSoft

private val LightScheme = lightColorScheme(
    primary = PhoneColors.Accent,
    onPrimary = Color.White,
    background = PhoneColors.Bg,
    onBackground = PhoneColors.Ink,
    surface = PhoneColors.Card,
    onSurface = PhoneColors.Ink,
    surfaceVariant = PhoneColors.AccentSoft,
    onSurfaceVariant = PhoneColors.Body,
    outline = PhoneColors.Line,
    error = PhoneColors.Warn,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7BC49B),
    onPrimary = Color(0xFF0E1512),
    background = PhoneColors.DarkBg,
    onBackground = PhoneColors.DarkInk,
    surface = PhoneColors.DarkCard,
    onSurface = PhoneColors.DarkInk,
    surfaceVariant = Color(0xFF262C32),
    onSurfaceVariant = PhoneColors.DarkBody,
    outline = PhoneColors.DarkLine,
    error = Color(0xFFE49171),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 系统栏图标深浅必须由这里校正：App 是深色而系统是浅色时，
    // 系统仍按浅色画深灰的前景图标，压在深底上几乎看不见（手势条同理）
    SystemBarsAppearance(dark = dark)
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

/**
 * 状态栏 / 手势条（小白条）图标深浅，跟随 App 内主题而不是系统主题。
 *
 * `isAppearanceLightNavigationBars` 同时管手势条那条小横杠的颜色，
 * 漏掉它就会出现「深色页面 + 一条看不见的深灰手势条」。
 */
@Composable
private fun SystemBarsAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
