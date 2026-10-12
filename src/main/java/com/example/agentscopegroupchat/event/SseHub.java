package com.example.agentscopegroupchat.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件中心：管理订阅者、广播命名事件、并保留有限历史用于断线重连补拉（Last-Event-ID）。
 *
 * <p>事件类型：message / typing / decision / usage / system / recall / state。
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private static final int HISTORY_MAX = 500;

    private record Envelope(long id, String type, String json) {
    }

    private final ObjectMapper mapper;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Deque<Envelope> history = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public SseHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter subscribe(String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由前端重连
        String key = UUID.randomUUID().toString();
        emitters.put(key, emitter);
        emitter.onCompletion(() -> emitters.remove(key));
        emitter.onTimeout(() -> {
            emitters.remove(key);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(key));

        long after = parseId(lastEventId);
        synchronized (history) {
            for (Envelope e : history) {
                if (e.id() > after) {
                    write(emitter, e);
                }
            }
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException ignored) {
            // 连接可能已断开
        }
        return emitter;
    }

    public void publish(String type, Object data) {
        String json;
        try {
            json = mapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("SSE 序列化失败 type={}：{}", type, e.getMessage());
            return;
        }
        Envelope env = new Envelope(seq.incrementAndGet(), type, json);
        synchronized (history) {
            history.addLast(env);
            while (history.size() > HISTORY_MAX) {
                history.removeFirst();
            }
        }
        emitters.forEach((key, emitter) -> {
            if (!write(emitter, env)) {
                emitters.remove(key);
            }
        });
    }

    public int clientCount() {
        return emitters.size();
    }

    private boolean write(SseEmitter emitter, Envelope env) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(env.id()))
                    .name(env.type())
                    .data(env.json(), MediaType.TEXT_PLAIN));
            return true;
        } catch (Exception e) {
            // 客户端已断开：静默关闭该 emitter，避免容器再做异步错误派发（否则会刷 ERROR 日志）
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已断开，忽略
            }
            return false;
        }
    }

    private static long parseId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}