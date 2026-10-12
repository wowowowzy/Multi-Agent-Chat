package com.example.agentscopegroupchat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 可选访问 token 校验（防止公网暴露被刷）。
 *
 * <p>仅保护 /api/**。token 可来自请求头 {@code X-Access-Token}，或查询参数 {@code token}
 * （SSE 场景 EventSource 无法自定义请求头，故支持 query 参数）。
 * 未配置 access-token 时直接放行。
 */
public class SecurityTokenInterceptor implements HandlerInterceptor {

    private final String accessToken;

    public SecurityTokenInterceptor(String accessToken) {
        this.accessToken = accessToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!StringUtils.hasText(accessToken)) {
            return true;
        }
        String provided = request.getHeader("X-Access-Token");
        if (!StringUtils.hasText(provided)) {
            provided = request.getParameter("token");
        }
        if (accessToken.equals(provided)) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"访问 token 校验失败\"}");
        return false;
    }
}