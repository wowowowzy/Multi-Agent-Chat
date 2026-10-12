package com.example.agentscopegroupchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** WebMVC 配置：为 /api/** 注册访问 token 拦截器。 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebMvcConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SecurityTokenInterceptor(props.getAccessToken()))
                .addPathPatterns("/api/**");
    }
}