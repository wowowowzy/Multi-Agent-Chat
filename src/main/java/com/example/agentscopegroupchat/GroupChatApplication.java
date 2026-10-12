package com.example.agentscopegroupchat;

import com.example.agentscopegroupchat.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentScope 多 Agent 群聊模拟系统启动类。
 *
 * <p>单体 Spring Boot WebMVC 应用：REST + SSE，SQLite 持久化，自研群调度器。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class GroupChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(GroupChatApplication.class, args);
    }
}