package com.example.agentscopegroupchat.holiday;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.holiday.HolidayCalendar.DayInfo;
import com.example.agentscopegroupchat.util.TimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 节假日/调休判定与降级单元测试。 */
class HolidayCalendarTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12); // 周一（工作日）
    private static final LocalDate SAT = LocalDate.of(2026, 10, 17); // 周六（普通周末）

    @Test
    void holidayWithNameBecomesVacation() {
        assertEquals("国庆节假期", HolidayCalendar.dayKind(new DayInfo(true, false, "国庆节", "holiday"),
                LocalDate.of(2026, 10, 1)));
        assertEquals("春节假期", HolidayCalendar.dayKind(new DayInfo(true, false, "春节", "holiday"),
                LocalDate.of(2026, 2, 17)));
    }

    /** 调休上班的周六：接口 type=workday，绝不能渲染成"周末"。 */
    @Test
    void adjustedWorkdayIsNotWeekend() {
        assertEquals("调休上班",
                HolidayCalendar.dayKind(new DayInfo(false, true, "国庆节调休", "workday"), LocalDate.of(2026, 10, 10)));
    }

    @Test
    void plainWorkdayAndWeekend() {
        assertEquals("工作日", HolidayCalendar.dayKind(new DayInfo(false, true, null, null), MON));
        assertEquals("周末", HolidayCalendar.dayKind(new DayInfo(true, false, null, null), SAT));
    }

    @Test
    void holidayNodeMissingNameStillNotVacation() {
        // isHoliday=true 但没有名字（普通周末）→ 按周末渲染
        assertEquals("周末", HolidayCalendar.dayKind(new DayInfo(true, false, "  ", "holiday"), SAT));
    }

    /** 拿不到数据（未启用 / 断网 / 接口变更）时必须退回"周一~周五=工作日"。 */
    @Test
    void nullInfoFallsBackToWeekdayRule() {
        assertEquals("工作日", HolidayCalendar.dayKind((DayInfo) null, MON));
        assertEquals("周末", HolidayCalendar.dayKind((DayInfo) null, SAT));
    }

    @Test
    void disabledApiDoesNotTouchNetwork() {
        AppProperties props = new AppProperties();
        props.getHoliday().setEnabled(false);
        HolidayCalendar cal = new HolidayCalendar(props, new ObjectMapper());
        assertEquals("工作日", cal.dayKind(MON));
        assertEquals("周末", cal.dayKind(SAT));
        assertEquals(TimeUtil.nowContext(LocalDateTime.of(2026, 10, 12, 14, 43)),
                cal.nowLine(LocalDateTime.of(2026, 10, 12, 14, 43)));
    }

    /** 接口不可达也不能抛异常，必须降级（这里指向一个必定拒绝连接的本地端口）。 */
    @Test
    void unreachableApiDegradesInsteadOfThrowing() {
        AppProperties props = new AppProperties();
        props.getHoliday().setEnabled(true);
        props.getHoliday().setBaseUrl("http://127.0.0.1:1/holidays/date");
        props.getHoliday().setTimeoutMs(300);
        props.getHoliday().setRetrySeconds(60);
        HolidayCalendar cal = new HolidayCalendar(props, new ObjectMapper());
        assertEquals("工作日", cal.dayKind(MON));
        // 换个日期会重新试一次接口，同样只降级、不抛异常
        assertEquals("周末", cal.dayKind(SAT));
    }

    /**
     * 真实接口路径 + 并发去重：门控是 N 个线程并行的，同一轮里只能打一次接口。
     * （曾经没加锁，5 个门控线程会各发一次 HTTP。）
     */
    @Test
    void httpPathWorksAndConcurrentCallsFetchOnlyOnce() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/holidays/date/", exchange -> {
            hits.incrementAndGet();
            byte[] body = ("{\"code\":0,\"msg\":\"success\",\"data\":{\"date\":\"2026-10-01\","
                    + "\"isHoliday\":true,\"isWorkday\":false,"
                    + "\"holiday\":{\"date\":\"2026-10-01\",\"name\":\"国庆节\",\"type\":\"holiday\"},"
                    + "\"dayOfWeek\":4}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AppProperties props = new AppProperties();
            props.getHoliday().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/holidays/date");
            HolidayCalendar cal = new HolidayCalendar(props, new ObjectMapper());
            LocalDate nationalDay = LocalDate.of(2026, 10, 1);

            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(pool.submit(() -> cal.dayKind(nationalDay)));
                }
                for (Future<String> f : futures) {
                    assertEquals("国庆节假期", f.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, hits.get(), "同一天并行调用只应打一次接口（缓存 + 串行化）");
            assertEquals("2026年10月1日 星期四 09:00（上午，国庆节假期）",
                    cal.nowLine(LocalDateTime.of(2026, 10, 1, 9, 0)));
        } finally {
            server.stop(0);
        }
    }
}