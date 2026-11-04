package cn.yiweiqiu.homework.core

import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class HomeworkConfig(
    /**
     * 云端目录。**可以不写**——config.json 里只留账号密码也能跑，
     * 缺省就用 [DEFAULT_URL]，目录不存在时由 [cn.yiweiqiu.homework.core.sync.SyncEngine]
     * 第一次同步时自动建出来。
     *
     * 之所以要能省掉：家长在电视上搬文件时最容易改错的就是地址，
     * 而这个值对 99% 的人来说永远是同一个。少写一行，就少一个出错的地方。
     */
    val webdavUrl: String = DEFAULT_URL,
    val username: String = "",
    val password: String = "",
    /**
     * 电视端自动问坚果云的间隔（分钟）。
     *
     * **0 = 不问**（默认）。此时只在这几种情况下联网：
     * 冷启动、回到前台、按刷新键、手机写完发的局域网通知、跨零点、6 小时兜底。
     *
     * 设成 1~60 才会恢复定时轮询——只有在家里的路由器拦广播、通知收不到时才需要。
     */
    val refreshIntervalMinutes: Int = 0,
    val childName: String = "",
) {
    val configured: Boolean get() = username.isNotBlank() && password.isNotBlank()

    /** 定时轮询是否启用 */
    val pollingEnabled: Boolean get() = refreshIntervalMinutes > 0

    /**
     * 把「写了但写歪了」的地址掰回来。
     *
     * 处理三种情况，都是 json 里手写地址时的常见错误：
     * 留空（只填了邮箱密码）、结尾漏了斜杠（拼出来会变成 `作业板day/x.json`）、
     * 前后带空格。地址不是 http(s) 开头的不去猜，原样留着交给错误提示。
     */
    fun normalized(): HomeworkConfig {
        val url = webdavUrl.trim()
        val fixed = when {
            url.isEmpty() -> DEFAULT_URL
            !url.startsWith("http://") && !url.startsWith("https://") -> url
            url.endsWith("/") -> url
            else -> "$url/"
        }
        return if (fixed == webdavUrl) this else copy(webdavUrl = fixed)
    }

    companion object {
        /** 坚果云固定目录，手机端导出的 config.json 不再写这一项 */
        const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/作业板/"
    }
}

/**
 * 凭据来源：外部文件，避免在电视上用遥控器输入邮箱密码。
 * 读取顺序：外部应用私有目录 → /sdcard/HomeBoard/
 */
object ConfigLoader {

    sealed class Result {
        data class Ok(val config: HomeworkConfig) : Result()
        data object Missing : Result()
        data class Bad(val reason: String) : Result()
    }

    private val candidatePaths: (Context) -> List<File> = { ctx ->
        val list = mutableListOf<File>()
        // 内部目录：便于 run-as / 后续应用内设置写入
        list += File(ctx.filesDir, "config.json")
        // 外部专属目录：adb push 的主通道，必须由应用自己先建好目录
        ctx.getExternalFilesDir(null)?.let {
            runCatching { it.mkdirs() }
            list += File(it, "config.json")
        }
        list
    }

    fun read(context: Context): Result {
        val files = candidatePaths(context)
        val hit = files.firstOrNull { it.isFile }
            ?: return Result.Missing

        val text = runCatching { hit.readText() }.getOrElse {
            return Result.Bad("读取 ${hit.absolutePath} 失败：${it.message}")
        }

        val config = runCatching { HomeworkJson.decodeFromString(HomeworkConfig.serializer(), text) }
            .getOrElse { return Result.Bad("config.json 格式错误：${it.message}") }

        if (config.username.isBlank() || config.password.isBlank()) {
            return Result.Bad("config.json 里 username / password 为空")
        }

        // 照着模板抄了一遍、没换成自己的账号。坚果云对中文 Basic Auth 直接回 400，
        // 不拦的话界面上只会显示「服务暂时不可用」，家长根本想不到是自己没改模板。
        if (config.username.contains("你的") || config.password.contains("应用密码")) {
            return Result.Bad(
                "config.json 还是模板内容：username 要换成坚果云登录邮箱，" +
                    "password 要换成「应用专用密码」（不是登录密码）",
            )
        }

        // 地址留空/漏斜杠/带空格都在这儿掰正，不让它变成后面难查的 404
        return Result.Ok(config.normalized())
    }

    /** 给引导页用：告诉家长该把文件放哪儿 */
    fun primaryPath(context: Context): String =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "config.json").absolutePath
}
