package cn.yiweiqiu.homework.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 作业按期规则。
 *
 * 这些日期是刻意挑的：2026-11-04 是周三，11-06 周五，11-07 周六，11-08 周日，11-09 周一。
 * 规则本身只有一句话，但星期几的算术最容易写错，所以用固定日期钉住。
 */
class DayRuleTest {

    private fun day(date: String, validUntil: String = "") =
        ManifestDay(date = date, validUntil = validUntil)

    /* ---------- 默认有效期：周五、周六布置的管到周日 ---------- */

    @Test
    fun `周五布置的管到周日`() {
        assertEquals("2026-11-08", DayRule.defaultValidUntil("2026-11-06"))
    }

    @Test
    fun `周六布置的也管到周日`() {
        assertEquals("2026-11-08", DayRule.defaultValidUntil("2026-11-07"))
    }

    @Test
    fun `周日布置的只管当天`() {
        assertEquals("2026-11-08", DayRule.defaultValidUntil("2026-11-08"))
    }

    @Test
    fun `周一到周四只管当天`() {
        listOf("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05").forEach { d ->
            assertEquals(d, DayRule.defaultValidUntil(d))
        }
    }

    /* ---------- 今天该显示哪一天 ---------- */

    @Test
    fun `周六周日看周五那份`() {
        val days = listOf(day("2026-11-06"))
        assertEquals("2026-11-06", DayRule.resolveViewDate("2026-11-07", days))
        assertEquals("2026-11-06", DayRule.resolveViewDate("2026-11-08", days))
    }

    @Test
    fun `周五一过就不再显示周四的`() {
        val days = listOf(day("2026-11-05"))
        assertEquals("2026-11-06", DayRule.resolveViewDate("2026-11-06", days))
    }

    @Test
    fun `到了周一 周五的就该翻篇`() {
        val days = listOf(day("2026-11-06"))
        assertEquals("2026-11-09", DayRule.resolveViewDate("2026-11-09", days))
    }

    @Test
    fun `今天有作业就用今天的`() {
        val days = listOf(day("2026-11-06"), day("2026-11-07"))
        assertEquals("2026-11-07", DayRule.resolveViewDate("2026-11-07", days))
    }

    /* ---------- 假期靠显式延长 ---------- */

    @Test
    fun `国庆布置的作业管到十月七日`() {
        val days = listOf(day("2026-09-30", validUntil = "2026-10-07"))
        assertEquals("2026-09-30", DayRule.resolveViewDate("2026-10-01", days))
        assertEquals("2026-09-30", DayRule.resolveViewDate("2026-10-07", days))
        assertEquals("2026-10-08", DayRule.resolveViewDate("2026-10-08", days))
    }

    @Test
    fun `有效期填到过去等于只管当天`() {
        assertEquals("2026-09-30", DayRule.validUntilOf("2026-09-30", "2026-09-01"))
    }

    @Test
    fun `有效期最多往后延十四天`() {
        assertEquals("2026-10-14", DayRule.validUntilOf("2026-09-30", "2027-01-01"))
    }

    @Test
    fun `空的显式值回落默认规则`() {
        assertEquals("", DayRule.normalizeValidUntil("2026-11-06", " "))
        assertEquals("2026-11-06", DayRule.normalizeValidUntil("2026-11-06", "2026-11-06"))
    }

    /* ---------- 跨月、跨年 ---------- */

    @Test
    fun `加天数能跨月跨年`() {
        assertEquals("2026-12-01", DayRule.addDays("2026-11-30", 1))
        assertEquals("2027-01-01", DayRule.addDays("2026-12-31", 1))
        assertEquals("2026-11-29", DayRule.addDays("2026-11-30", -1))
    }

    @Test
    fun `周日取本周日`() {
        assertEquals("2026-11-08", DayRule.nextSunday("2026-11-06"))
        assertEquals("2026-11-08", DayRule.nextSunday("2026-11-08"))
    }
}
