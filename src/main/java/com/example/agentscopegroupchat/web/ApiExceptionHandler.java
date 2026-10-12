package com.example.agentscopegroupchat.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常兜底：返回 JSON 而非 Whitelabel 错误页，避免前端解析失败。
 *
 * <p>注意：SSE 是异步响应，客户端断开时容器会走异步错误派发。这类异常必须静默处理，
 * 且不能对 {@code text/event-stream} 响应写 JSON（无可用转换器，会二次报错刷日志）。
 *
 * <p>另一个必须显式处理的点：Spring 的参数绑定异常（缺参 / 类型不对 / 媒体类型不对）
 * 都是<b>客户端</b>错误，若不在这里映射，就会落到下面的 {@code Exception} 兜底被判成 500，
 * 让调用方以为服务端挂了。
 *
 * <p>本 Advice 只作用于 {@code web} 包下的 Controller（见 {@code basePackages}）。所以
 * "路径存在但方法不对"（405）这类在<b>查找 handler 阶段</b>抛出的异常不归它管，
 * 交由 Spring 默认解析器返回 405（状态码与 {@code Allow} 头都正确，body 是 Boot 的标准错误 JSON）。
 */
@RestControllerAdvice(basePackages = "com.example.agentscopegroupchat.web")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "请求体不是合法 JSON");
    }

    /** 缺少必需参数（如 /api/decisions 不带 messageId）：客户端错误，400 而非 500。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "缺少必需参数：" + e.getParameterName());
    }

    /** 参数类型不对（如 messageId=abc / limit=abc / 路径里的 id=abc）：同为客户端错误，400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "参数 " + e.getName() + " 类型不正确：" + e.getValue());
    }

    /** Content-Type 不对（如给只收 JSON 的接口发了 text/plain）。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求体类型：" + e.getContentType());
    }

    /** SSE 客户端断开：静默忽略，不写响应体、不记 ERROR。 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("SSE 客户端已断开：{}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e, HttpServletResponse response) {
        if (isEventStream(response)) {
            // 流式响应已开始，无法再写 JSON；交由容器正常结束该连接
            log.debug("SSE 流异常（已忽略）：{}", e.getMessage());
            return null;
        }
        log.error("接口异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    private static boolean isEventStream(HttpServletResponse response) {
        String ct = response.getContentType();
        return ct != null && ct.contains("text/event-stream");
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status.value());
        body.put("message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }
}