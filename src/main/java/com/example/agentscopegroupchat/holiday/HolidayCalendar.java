package com.example.agentscopegroupchat.holiday;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.util.TimeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 中国法定节假日 / 调休查询（可选增强）。
 *
 * <p>只按星期几判断"上不上班"会把国庆、春节、以及"调休上班的周六"全判错，于是 agent 会在
 * 假期里说"上班加油"、在调休周六说"周末愉快"。这里查第三方接口拿到真实作息。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>必须缓存</b>：拼一次 prompt 就调一次 {@link #nowLine()}，绝不能每次打网络；</li>
 *   <li><b>失败必须降级</b>：断网 / 接口变更 / 超时都退回"周一~周五=工作日"，绝不抛异常影响群聊；</li>
 *   <li><b>失败也别猛打</b>：失败后按 {@code retry-seconds} 冷却，避免把上游打挂。</li>
 * </ul>
 */
@Component
public class HolidayCalendar {

    private static final Logger log = LoggerFactory.getLogger(HolidayCalendar.class);

    /** 接口 {@code data} 节点的作息信息。 */
    public record DayInfo(boolean isHoliday, boolean isWorkday, String name, String type) {
    }

    private record Cached(String date, String label, long atMillis) {
    }

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private volatile Cached cached;
    private final Object fetchLock = new Object();

    public HolidayCalendar(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 供 prompt 使用的"当前时间"整行，例如 {@code 2026年10月1日 星期四 09:00（上午，国庆节假期）}。 */
    public String nowLine() {
        return nowLine(LocalDateTime.now());
    }

    /** {@link #nowLine()} 的可注入版本。 */
    public String nowLine(LocalDateTime now) {
        return TimeUtil.nowContext(now, dayKind(now.toLocalDate()));
    }

    /** 该日期的作息描述：工作日 / 周末 / {节日}假期 / 调休上班。查不到时按星期几兜底。 */
    public String dayKind(LocalDate date) {
        if (!props.getHoliday().isEnabled()) {
            return fallback(date);
        }
        String key = date.toString();
        String hit = cachedLabel(date);
        if (hit != null) {
            return hit;
        }
        // 门控是 N 个线程并行的，必须串行化首次拉取：否则同一轮里 5 个线程会各打一次接口。
        synchronized (fetchLock) {
            hit = cachedLabel(date);
            if (hit != null) {
                return hit;
            }
            long nowMs = System.currentTimeMillis();
            DayInfo info = fetch(date);
            if (info == null) {
                cached = new Cached(key, null, nowMs);
                // fetch 内部已记录了失败原因，这里只补一句"降级成了什么 + 何时重试"
                log.warn("{} 节假日查询失败，暂按「{}」处理（{} 秒后重试；可设 app.holiday.enabled=false 关掉）",
                        key, fallback(date), props.getHoliday().getRetrySeconds());
                return fallback(date);
            }
            String label = dayKind(info, date);
            cached = new Cached(key, label, nowMs);
            log.info("节假日接口：{} → {}（isHoliday={}, isWorkday={}, 名称={}）",
                    key, label, info.isHoliday(), info.isWorkday(), info.name());
            return label;
        }
    }

    /** 命中缓存则返回作息描述；失败缓存处于冷却期返回兜底值；未命中返回 null。 */
    private String cachedLabel(LocalDate date) {
        Cached c = cached;
        if (c == null || !c.date().equals(date.toString())) {
            return null;
        }
        if (c.label() != null) {
            return c.label();
        }
        return System.currentTimeMillis() - c.atMillis() < retryCooldownMs() ? fallback(date) : null;
    }

    private long retryCooldownMs() {
        return Math.max(30, props.getHoliday().getRetrySeconds()) * 1000L;
    }

    /**
     * 纯函数：把接口结果映射成中文作息描述（便于单测）。
     *
     * <p>{@code info == null}（未启用 / 查询失败）时退回"周一~周五=工作日"。
     */
    public static String dayKind(DayInfo info, LocalDate date) {
        if (info == null) {
            return fallback(date);
        }
        if ("workday".equalsIgnoreCase(info.type())) {
            return "调休上班";
        }
        if (info.isHoliday() && info.name() != null && !info.name().isBlank()) {
            return info.name().strip() + "假期";
        }
        return info.isWorkday() ? "工作日" : "周末";
    }

    private static String fallback(LocalDate date) {
        return TimeUtil.isWorkday(date) ? "工作日" : "周末";
    }

    private DayInfo fetch(LocalDate date) {
        String base = props.getHoliday().getBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String url = base.replaceAll("/+$", "") + "/" + date;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("节假日接口 HTTP {}：{}", res.statusCode(), url);
                return null;
            }
            JsonNode data = mapper.readTree(res.body()).path("data");
            if (!data.isObject()) {
                log.warn("节假日接口返回结构不认识：{}", abbreviate(res.body()));
                return null;
            }
            JsonNode holiday = data.path("holiday");
            String name = holiday.isObject() ? holiday.path("name").asText(null) : null;
            String type = holiday.isObject() ? holiday.path("type").asText(null) : null;
            return new DayInfo(data.path("isHoliday").asBoolean(false),
                    data.path("isWorkday").asBoolean(false), name, type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("节假日接口不可用（{}）：{}", url, e.getMessage());
            return null;
        }
    }

    private int timeoutMs() {
        return Math.max(200, props.getHoliday().getTimeoutMs());
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}