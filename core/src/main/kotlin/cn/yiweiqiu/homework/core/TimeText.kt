package cn.yiweiqiu.homework.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 契约里的时间格式：ISO8601 带本地时区偏移，如 `2026-11-03T20:10:00+08:00`。
 *
 * 刻意不用 java.time：minSdk 23 上要开 desugaring，为几个时间格式化引一整套
 * 核心库替换不值得。
 */
object IsoTime {

    fun now(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(Date())
        return stamp + offsetOfNow()
    }

    private fun offsetOfNow(): String {
        val cal = Calendar.getInstance()
        val minutes = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60000
        val sign = if (minutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(minutes)
        return "%s%02d:%02d".format(sign, abs / 60, abs % 60)
    }

    /**
     * 把带偏移的 ISO 时间转成 epoch 毫秒，用于条目级「谁更新」的比较。
     *
     * 空串、格式不对一律返回 0——老数据和网页端写的是空串，语义上就该输给任何真实时间。
     */
    fun toMillis(iso: String): Long {
        if (iso.length < 19) return 0L
        val wall = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).parse(iso.substring(0, 19))
        }.getOrNull() ?: return 0L

        // 上面按本机时区解析，得到的是「把这串墙钟时间当本机时间」的时刻；
        // 要还原真实时刻，先补回本机偏移，再减掉写入方的偏移。
        val defaultOffset = TimeZone.getDefault().getOffset(wall.time)
        val writerOffset = offsetMillisOf(iso.substring(19))
        return wall.time + defaultOffset - writerOffset
    }

    /** "+08:00" / "+0800" / "Z" → 毫秒 */
    private fun offsetMillisOf(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        if (s == "Z" || s == "z") return 0L
        val sign = when (s[0]) {
            '-' -> -1
            '+' -> 1
            else -> return 0L
        }
        val digits = s.drop(1).replace(":", "")
        if (digits.length < 4) return 0L
        val h = digits.substring(0, 2).toIntOrNull() ?: return 0L
        val m = digits.substring(2, 4).toIntOrNull() ?: return 0L
        return sign * (h * 60L + m) * 60_000L
    }
}
