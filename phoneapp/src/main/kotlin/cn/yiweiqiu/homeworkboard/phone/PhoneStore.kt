package cn.yiweiqiu.homeworkboard.phone

import android.content.Context
import cn.yiweiqiu.homework.core.HomeworkConfig
import java.io.File

/**
 * 手机端的设置存储。
 *
 * 刻意用 MODE_PRIVATE 的 SharedPreferences 而不是 Room/DataStore：
 * 需要保存的东西就 6 个标量，多引一个依赖不值当。凭据只在应用私有目录，
 * 不进日志、不进 UI 明文展示（设置页密码框默认打码）。
 */
class PhoneStore(context: Context) {

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)

    var webdavUrl: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("url", v.trim()).apply()

    var username: String
        get() = prefs.getString("user", "").orEmpty()
        set(v) = prefs.edit().putString("user", v.trim()).apply()

    var password: String
        get() = prefs.getString("pass", "").orEmpty()
        set(v) = prefs.edit().putString("pass", v.trim()).apply()

    var childName: String
        get() = prefs.getString("child", "").orEmpty()
        set(v) = prefs.edit().putString("child", v.trim()).apply()

    /** 记住上次用的科目，让"再记一条"只要点两下 */
    var lastSubject: String
        get() = prefs.getString("lastSubject", "语文") ?: "语文"
        set(v) = prefs.edit().putString("lastSubject", v).apply()

    /** 草稿只保留一条，覆盖式（契约 5.2） */
    var draft: String
        get() = prefs.getString("draft", "").orEmpty()
        set(v) = prefs.edit().putString("draft", v).apply()

    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    /** 地址留空也能用：normalized() 会补成坚果云默认目录 */
    fun toConfig(): HomeworkConfig = HomeworkConfig(
        webdavUrl = webdavUrl,
        username = username,
        password = password,
        childName = childName,
    ).normalized()

    /**
     * 给电视端用的配置文本。
     *
     * **只放账号和应用密码两项**。地址不写：它永远是同一个，
     * 抄进去只会多一个「抄错一个字符、电视端半年不干活」的机会。
     * 电视端读到缺省地址会用默认值，云端目录不存在会自己 MKCOL 建出来。
     */
    fun configJson(): String = """
        {
          "username": "${jsonEscape(username)}",
          "password": "${jsonEscape(password)}"
        }
    """.trimIndent()

    private fun jsonEscape(raw: String): String = buildString {
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /**
     * 把同一份配置落到外部私有目录，方便在电脑上 `adb pull` 出来再推给电视。
     * 电视端没有输入框、也不可能让家长用遥控器敲密码，这个文件就是唯一的过河方式。
     */
    fun exportForTv(): File? {
        if (!isConfigured) return null
        val dir = ctx.getExternalFilesDir(null) ?: return null
        runCatching { dir.mkdirs() }
        val file = File(dir, "config.json")
        return runCatching { file.writeText(configJson()); file }.getOrNull()
    }

    companion object {
        /** 和电视端共用同一个默认值，避免两边改了一边忘了另一边 */
        const val DEFAULT_URL: String = HomeworkConfig.DEFAULT_URL
    }
}
