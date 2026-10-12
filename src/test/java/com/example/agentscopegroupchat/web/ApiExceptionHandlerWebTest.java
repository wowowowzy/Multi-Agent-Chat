package com.example.agentscopegroupchat.web;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentscopegroupchat.config.AppProperties;
import com.example.agentscopegroupchat.event.SseHub;
import com.example.agentscopegroupchat.scheduler.GroupScheduler;
import com.example.agentscopegroupchat.store.DecisionLogDao;
import com.example.agentscopegroupchat.store.GroupDao;
import com.example.agentscopegroupchat.store.MessageDao;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 参数绑定错误的 HTTP 状态码契约。
 *
 * <p><b>为什么需要这个测试：</b>Spring 的参数绑定异常（缺参 / 类型不对 / 方法不对 / 媒体类型不对）
 * 曾经只被 {@code @ExceptionHandler(Exception.class)} 兜底，于是客户端传错参数被误报成 <b>500</b>。
 * 这里用真实的 Controller + {@link ApiExceptionHandler} 钉死"客户端错误 → 4xx"。
 *
 * <p>这些请求都在参数绑定阶段就被拒了，压根没进方法体，所以各依赖（DAO / 调度器 / SSE）用空 mock 即可。
 */
class ApiExceptionHandlerWebTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChatController chat = new ChatController(mock(AppProperties.class), mock(MessageDao.class),
                mock(GroupDao.class), mock(GroupScheduler.class), mock(SseHub.class));
        DecisionController decisions = new DecisionController(mock(DecisionLogDao.class));
        mvc = MockMvcBuilders.standaloneSetup(chat, decisions)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void missingRequiredParamIs400() throws Exception {
        mvc.perform(get("/api/decisions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(400));
    }

    @Test
    void nonNumericQueryParamIs400() throws Exception {
        mvc.perform(get("/api/decisions").param("messageId", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("limit", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("before", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonNumericPathVariableIs400() throws Exception {
        mvc.perform(post("/api/messages/abc/recall"))
                .andExpect(status().isBadRequest());
    }

    /** 报错信息要能指出是哪个参数，否则前端只能靠猜。 */
    @Test
    void errorMessageNamesTheOffendingParameter() throws Exception {
        mvc.perform(get("/api/decisions"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("messageId")));
        mvc.perform(get("/api/messages").param("limit", "abc"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("limit")));
    }

    /** 同类客户端错误：方法不对 → 405，媒体类型不对 → 415（都曾是 500）。 */
    @Test
    void wrongMethodIs405() throws Exception {
        // 注意：405 是在"找 handler"阶段抛的，handler 为 null 时本 Advice（限定 web 包）不生效，
        // 由 Spring 默认解析器给出状态码；这里只钉住"不再被兜底成 500"。
        mvc.perform(post("/api/decisions"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post("/api/messages").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /** 仍然是个 JSON body，前端不会拿到 Whitelabel 页面。 */
    @Test
    void errorsStayJson() throws Exception {
        mvc.perform(get("/api/decisions"))
                .andExpect(jsonPath("$.message").exists());
    }
}