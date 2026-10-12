package com.example.agentscopegroupchat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 基础设施 Bean：SQLite DataSource。 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public DataSource dataSource(AppProperties props, Environment environment) throws IOException {
        Path db = Paths.get(props.getDbPath()).toAbsolutePath();
        Path parent = db.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String ext = NativeLibraryResolver.resolve(props.getVec());
        // .env 是在 EnvironmentPostProcessor 阶段加载的（那时日志系统还没起来），
        // 所以把"有没有找到 .env"放到这里提示，避免"没配模型却不报原因"。
        String envFile = environment.getProperty(DotenvEnvironmentPostProcessor.LOADED_PATH_KEY);
        log.info("配置文件 .env：{}", envFile == null
                ? "未找到（如需调用大模型，请复制 .env.example 为 .env 并填写；仅用环境变量也可）"
                : envFile);
        log.info("SQLite 数据库：{}", db);
        log.info("sqlite-vec 原生扩展：{}", ext == null ? "未启用（走纯 Java 兜底）" : ext);
        return new SqliteDataSource(db.toString(), ext);
    }
}