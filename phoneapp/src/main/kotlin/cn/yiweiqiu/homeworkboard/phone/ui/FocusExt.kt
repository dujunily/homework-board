package cn.yiweiqiu.homeworkboard.phone.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * 按下就把焦点和键盘一起要过来。
 *
 * 只在 Initial pass **观察**、不消费事件，所以不影响 TextField 自己的光标定位。
 * 存在的理由：MIUI 上偶发「手指点进输入框、焦点却没落进去」，
 * 表现就是怎么点都不弹键盘；这里不等系统判断，按下就直接 requestFocus + show。
 */
internal fun Modifier.tapToFocus(
    fr: FocusRequester,
    kb: SoftwareKeyboardController?,
): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
                runCatching { fr.requestFocus() }
                kb?.show()
            }
        }
    }
}
