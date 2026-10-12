package com.example.agentscopegroupchat.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析 sqlite-vec 原生扩展路径。
 *
 * <p>优先级：显式配置路径 &gt; classpath 内置动态库（提取到临时文件）&gt; 关闭扩展。
 * 关闭扩展时系统自动走纯 Java 余弦兜底，功能不受影响。
 */
public final class NativeLibraryResolver {

    private static final Logger log = LoggerFactory.getLogger(NativeLibraryResolver.class);

    private NativeLibraryResolver() {
    }

    /**
     * @return 扩展绝对路径；null 表示不使用原生扩展
     */
    public static String resolve(AppProperties.Vec vec) {
        if (vec == null || !vec.isEnabled()) {
            return null;
        }
        String configured = vec.getExtensionPath();
        if (configured != null && !configured.isBlank()) {
            if ("none".equalsIgnoreCase(configured.trim())) {
                return null;
            }
            File f = new File(configured.trim());
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
            log.warn("配置的 sqlite-vec 扩展路径不存在，改用内置库或纯 Java 兜底：{}", configured);
        }
        return extractBundled();
    }

    /** 从 classpath 的 native/&lt;platform&gt;/ 提取内置动态库到临时文件。 */
    private static String extractBundled() {
        String resource = resourceName();
        if (resource == null) {
            return null;
        }
        try (InputStream in = NativeLibraryResolver.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.info("classpath 未找到内置 sqlite-vec 库 {}，将走纯 Java 兜底", resource);
                return null;
            }
            String suffix = resource.substring(resource.lastIndexOf('.'));
            Path tmp = Files.createTempFile("sqlite-vec-", suffix);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            log.warn("提取内置 sqlite-vec 库失败：{}", e.getMessage());
            return null;
        }
    }

    private static String resourceName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String a = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
        if (os.contains("win")) {
            return "/native/windows-" + a + "/vec0.dll";
        }
        if (os.contains("linux")) {
            return "/native/linux-" + a + "/vec0.so";
        }
        if (os.contains("mac")) {
            return "/native/macos-" + a + "/vec0.dylib";
        }
        return null;
    }
}