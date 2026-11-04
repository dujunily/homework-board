package cn.yiweiqiu.homework.core

import java.io.File
import java.util.Calendar

/** 本地文件缓存：元数据用原始 json 文本，图片缓存留给 P1（Coil 磁盘缓存） */
class LocalCache(dir: File) {

    private val root = dir.apply { mkdirs() }

    private fun file(name: String) = File(root, name)

    fun readText(name: String): String? =
        runCatching { file(name).takeIf { it.isFile }?.readText() }.getOrNull()

    fun writeText(name: String, value: String) {
        runCatching { file(name).writeText(value) }
    }

    fun delete(name: String) {
        runCatching { file(name).delete() }
    }

    fun lastCheckedAt(): Long = runCatching { file("last-checked").readText().toLong() }.getOrDefault(0L)

    fun setLastCheckedAt(v: Long) = writeText("last-checked", v.toString())

    companion object {
        fun manifestEtagName() = "etag-manifest"
        fun dayEtagName(date: String) = "etag-day-$date"
        fun dayCacheName(date: String) = "day-$date.json"
        fun manifestCacheName() = "manifest.json"
    }
}

/** 日期工具：刻意不用 java.time，minSdk 23 上会引入 desugaring 成本 */
object DayKeys {
    private val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun today(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun weekdayOf(date: String): String = runCatching {
        val (y, m, d) = date.split("-").map { it.toInt() }
        val c = Calendar.getInstance().apply { set(y, m - 1, d) }
        weekdays[c.get(Calendar.DAY_OF_WEEK) - 1]
    }.getOrDefault("")

    /** "2026-11-03" -> "11月3日 周二" */
    fun label(date: String): String = runCatching {
        val parts = date.split("-")
        val m = parts[1].toInt()
        val d = parts[2].toInt()
        "${m}月${d}日 ${weekdayOf(date)}".trim()
    }.getOrDefault(date)

    /** "2026-11-03T20:10:00+08:00" -> "20:10" */
    fun timeOf(iso: String): String =
        if (iso.length >= 16 && iso[10] == 'T') iso.substring(11, 16) else ""
}
