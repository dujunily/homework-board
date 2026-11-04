package cn.yiweiqiu.homework.core

import java.util.Calendar

/**
 * 「哪天布置的作业，管到哪天」。
 *
 * 作业是按天存的，但看的动作不按天发生：周五布置的作业，周六周日都在做。
 * 电视端如果只认「今天」，周五晚上一过就变成空白，整个周末都是空白——
 * 这正是原来最别扭的地方（家长周五记完，周六早上打开电视什么都没有）。
 *
 * 规则：
 * 1. 每一天布置的作业有一个**有效期**。周五、周六布置的默认管到周日，其余管到当天。
 * 2. 今天有自己的作业 → 用今天的（布置了新的，旧的自动翻篇）。
 * 3. 今天没有 → 沿用最近一天仍在有效期内的作业。
 * 4. 假期、调休没有规律可推，由家长在手机上把有效期往后延（[HomeworkDay.validUntil]）。
 *
 * 只往回看 [MAX_CARRY_DAYS] 天：连休最长也就一周多，再久多半是数据出了问题，
 * 不该让一条陈年作业永远挂在电视上。
 *
 * 日期一律用 `yyyy-MM-dd`，可以直接按字符串比大小。
 */
object DayRule {

    const val MAX_CARRY_DAYS = 14

    private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    fun isDate(text: String): Boolean = DATE_PATTERN.matches(text.trim())

    fun addDays(date: String, days: Int): String {
        val c = calOf(date) ?: return date
        c.add(Calendar.DAY_OF_YEAR, days)
        return format(c)
    }

    /** 1 = 周日，2 = 周一 …… 7 = 周六（沿用 [Calendar] 的编号，不另造一套） */
    fun dayOfWeek(date: String): Int = calOf(date)?.get(Calendar.DAY_OF_WEEK) ?: Calendar.MONDAY

    /**
     * 没有显式设置时的有效期：周五、周六布置的管到周日，其余管到当天。
     *
     * 用「下一个上学日的前一天」来理解也一样，只是这里不认法定节假日——
     * 假期要靠家长显式延长。
     */
    fun defaultValidUntil(date: String): String = when (dayOfWeek(date)) {
        Calendar.FRIDAY -> addDays(date, 2)
        Calendar.SATURDAY -> addDays(date, 1)
        else -> date
    }

    /** 本周的周日（当天就是周日则返回当天），用来表达「管到周末」 */
    fun nextSunday(date: String): String {
        val dow = dayOfWeek(date)
        val add = (Calendar.SUNDAY - dow + 7) % 7
        return addDays(date, add)
    }

    /**
     * 显式值合法就用显式的，否则回落默认值。     *
     * 允许的区间是 `[date, date + MAX_CARRY_DAYS]`：
     * 填到过去等于「只管今天」，填到太远会被截断，免得一条写错的日期把电视钉死。
     */
    fun validUntilOf(date: String, explicit: String): String {
        val v = explicit.trim()
        if (!isDate(v)) return defaultValidUntil(date)
        return when {
            v < date -> date
            v > addDays(date, MAX_CARRY_DAYS) -> addDays(date, MAX_CARRY_DAYS)
            else -> v
        }
    }

    /** 校验并规范化家长填的有效期；空串保留为空串（= 走默认规则） */
    fun normalizeValidUntil(date: String, input: String): String =
        if (input.isBlank()) "" else validUntilOf(date, input)

    /**
     * 今天该显示哪一天的作业。
     *
     * 今天有自己的一份就直接用它；否则在有效期内挑最近的一天。
     * 一天都没有（新装、刚清空）就返回今天——调用方照常显示「今天还没布置」。
     */
    fun resolveViewDate(today: String, days: List<ManifestDay>): String {
        if (days.isEmpty()) return today
        if (days.any { it.date == today }) return today

        val earliest = addDays(today, -MAX_CARRY_DAYS)
        return days
            .asSequence()
            .filter { it.date < today && it.date >= earliest }
            .filter { validUntilOf(it.date, it.validUntil) >= today }
            .maxByOrNull { it.date }
            ?.date
            ?: today
    }

    /** 「11月1日 周日」→「周日」这种短称呼，给界面上的小标签用 */
    fun weekdayShort(date: String): String = DayKeys.weekdayOf(date)

    private fun calOf(date: String): Calendar? {
        val parts = date.trim().split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return Calendar.getInstance().apply {
            // 固定到正午：跨夏令时加减天数时不会因为「差一小时」跳到前一天
            set(y, m - 1, d, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun format(c: Calendar): String = "%04d-%02d-%02d".format(
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
    )
}
