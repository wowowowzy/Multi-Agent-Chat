package com.example.agentscopegroupchat.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** 时间工具：统一使用 {@code yyyy-MM-dd HH:mm:ss} 文本存储，便于 SQLite 排序。 */
public final class TimeUtil {

    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M月d日");

    private TimeUtil() {
    }

    public static String now() {
        return LocalDateTime.now().format(FMT);
    }

    /** 中文星期，如「星期一」。 */
    public static String weekdayZh(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    }

    /** 是否工作日（周一~周五）。法定节假日本机无从判断，只区分周末。 */
    public static boolean isWorkday(LocalDate date) {
        DayOfWeek w = date.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
    }

    /** 一天中的口语时段：凌晨 / 早上 / 上午 / 中午 / 下午 / 傍晚 / 晚上 / 深夜。 */
    public static String timeOfDay(LocalTime time) {
        int h = time.getHour();
        if (h < 5) {
            return "凌晨";
        }
        if (h < 8) {
            return "早上";
        }
        if (h < 11) {
            return "上午";
        }
        if (h < 13) {
            return "中午";
        }
        if (h < 17) {
            return "下午";
        }
        if (h < 19) {
            return "傍晚";
        }
        if (h < 23) {
            return "晚上";
        }
        return "深夜";
    }

    /**
     * 注入 prompt 的"当前时间"描述，例如：{@code 2026年10月12日 星期一 14:43（下午，工作日）}。
     *
     * <p>模型如果不被告知真实时间，就会按语料习惯臆测"大晚上的"——这是实测踩过的坑。
     */
    public static String nowContext() {
        return nowContext(LocalDateTime.now());
    }

    /** {@link #nowContext()} 的可注入版本，便于测试。 */
    public static String nowContext(LocalDateTime now) {
        return nowContext(now, isWorkday(now.toLocalDate()) ? "工作日" : "周末");
    }

    /**
     * 用调用方给定的"今天上不上班"描述拼装当前时间，例如 {@code dayKind = "国庆节假期" / "调休上班"}。
     *
     * <p>节假日的准确判定需要外部日历（见 {@code HolidayCalendar}）；这里只负责拼字符串，
     * {@code dayKind} 为空时退回"周一~周五=工作日"。
     */
    public static String nowContext(LocalDateTime now, String dayKind) {
        String kind = dayKind == null || dayKind.isBlank()
                ? (isWorkday(now.toLocalDate()) ? "工作日" : "周末")
                : dayKind.strip();
        return now.format(CN_DATE) + " " + weekdayZh(now.toLocalDate()) + " " + now.format(HM)
                + "（" + timeOfDay(now.toLocalTime()) + "，" + kind + "）";
    }

    /** 聊天记录里的时间分隔标签：今天 / 昨天 / M月d日 + HH:mm。 */
    public static String dayStamp(LocalDate date, LocalTime time, LocalDate today) {
        String day = date.equals(today) ? "今天"
                : date.equals(today.minusDays(1)) ? "昨天"
                : date.format(MD);
        return "[" + day + " " + time.format(HM) + "]";
    }

    /** 当天 0 点的时间字符串。 */
    public static String todayStart() {
        return LocalDateTime.now().toLocalDate().atStartOfDay().format(FMT);
    }

    /** 解析时间字符串，失败返回 null。 */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, FMT);
        } catch (Exception e) {
            return null;
        }
    }
}