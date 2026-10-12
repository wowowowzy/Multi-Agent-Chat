package com.example.agentscopegroupchat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** 时间上下文（注入 prompt 的"现在几点、星期几、上不上班"）单元测试。 */
class TimeUtilTest {

    @Test
    void weekdayAndWorkday() {
        assertEquals("星期一", TimeUtil.weekdayZh(LocalDate.of(2026, 10, 12)));
        assertTrue(TimeUtil.isWorkday(LocalDate.of(2026, 10, 12)));   // 周一
        assertTrue(TimeUtil.isWorkday(LocalDate.of(2026, 10, 16)));   // 周五
        assertFalse(TimeUtil.isWorkday(LocalDate.of(2026, 10, 17)));  // 周六
        assertFalse(TimeUtil.isWorkday(LocalDate.of(2026, 10, 18)));  // 周日
    }

    @Test
    void timeOfDayBuckets() {
        assertEquals("凌晨", TimeUtil.timeOfDay(LocalTime.of(3, 0)));
        assertEquals("早上", TimeUtil.timeOfDay(LocalTime.of(6, 30)));
        assertEquals("上午", TimeUtil.timeOfDay(LocalTime.of(9, 0)));
        assertEquals("中午", TimeUtil.timeOfDay(LocalTime.of(12, 0)));
        assertEquals("下午", TimeUtil.timeOfDay(LocalTime.of(14, 43)));
        assertEquals("傍晚", TimeUtil.timeOfDay(LocalTime.of(18, 0)));
        assertEquals("晚上", TimeUtil.timeOfDay(LocalTime.of(21, 0)));
        assertEquals("深夜", TimeUtil.timeOfDay(LocalTime.of(23, 30)));
    }

    /** 下午就该说"下午、工作日"，这正是模型臆测"大晚上的"的反例。 */
    @Test
    void nowContextDescribesAfternoonOfWorkday() {
        String c = TimeUtil.nowContext(LocalDateTime.of(2026, 10, 12, 14, 43));
        assertEquals("2026年10月12日 星期一 14:43（下午，工作日）", c);
    }

    @Test
    void nowContextMarksWeekend() {
        String c = TimeUtil.nowContext(LocalDateTime.of(2026, 10, 17, 21, 5));
        assertTrue(c.contains("星期六"), c);
        assertTrue(c.contains("晚上"), c);
        assertTrue(c.contains("周末"), c);
    }

    @Test
    void dayStampUsesRelativeWords() {
        LocalDate today = LocalDate.of(2026, 10, 12);
        assertEquals("[今天 14:43]", TimeUtil.dayStamp(today, LocalTime.of(14, 43), today));
        assertEquals("[昨天 09:20]", TimeUtil.dayStamp(today.minusDays(1), LocalTime.of(9, 20), today));
        assertEquals("[10月10日 08:00]", TimeUtil.dayStamp(today.minusDays(2), LocalTime.of(8, 0), today));
    }
}