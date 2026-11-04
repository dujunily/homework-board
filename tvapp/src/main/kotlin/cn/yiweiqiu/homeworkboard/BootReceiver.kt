package cn.yiweiqiu.homeworkboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 电视开机后直接把作业板拉起来，孩子开电视即见作业，不用找遥控器去桌面点图标。
 *
 * 注意：Android 10+ 对"后台启动 Activity"有系统级限制，部分厂商 ROM 会拦掉这次 startActivity。
 * 拦掉时这里只记一条日志，不弹任何东西（孩子面前弹报错最糟）。README 里写了小米电视
 * 对应的开发者/安全设置路径，实测走白名单最稳。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val accepted = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.miui.intent.action.QUICKBOOT_POWERON"
        if (!accepted) return

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }.onFailure {
            Log.w("HomeworkBoard", "开机自启被系统拦下：${it.javaClass.simpleName}")
        }
    }
}
