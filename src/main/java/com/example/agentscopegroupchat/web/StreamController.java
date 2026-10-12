package com.example.agentscopegroupchat.web;

import com.example.agentscopegroupchat.event.SseHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 端点（5.3）：推送 message / typing / decision / usage / system / recall / state 事件。
 *
 * <p>断线重连时前端浏览器会自动带上 {@code Last-Event-ID} 头，{@link SseHub} 据此补发断连期间
 * 遗漏的事件；同时兼容显式查询参数 {@code lastEventId}，方便手动测试（curl）。
 */
@RestController
public class StreamController {

    private final SseHub sseHub;

    public StreamController(SseHub sseHub) {
        this.sseHub = sseHub;
    }

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "lastEventId", required = false) String lastEventIdParam) {
        String since = lastEventId != null ? lastEventId : lastEventIdParam;
        return sseHub.subscribe(since);
    }
}