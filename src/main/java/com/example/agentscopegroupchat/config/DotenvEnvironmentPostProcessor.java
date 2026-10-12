package com.example.agentscopegroupchat.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 把根目录 {@code .env} 读进 Spring Environment（真正的 dotenv 语义）。
 *
 * <p><b>为什么不用 {@code spring.config.import=optional:file:./.env[.properties]}：</b>
 * 那个写法把 .env 当 properties 解析，而 properties 不认 .env 的常见写法：
 *
 * <ul>
 *   <li>{@code KEY="sk-xxx"} —— 引号会被当成值的一部分，鉴权静默失败（最难查的一类问题）；</li>
 *   <li>{@code export KEY=value} —— 整个 "export KEY" 会被当成键名；</li>
 *   <li>{@code KEY=value # 注释} —— 行尾注释会进值里。</li>
 * </ul>
 *
 * <p>开源后必然会有人按自己的习惯写 .env，所以这里自己实现解析：容忍引号、{@code export}
 * 前缀、行尾注释、CRLF、{@code KEY=} 空值，且 {@code =} 只按第一个切分（值里可以再有 =）。
 *
 * <p><b>查找顺序</b>：{@code -Ddotenv.location=...} 显式指定 → 从当前工作目录向上最多
 * 5 层找 {@code .env}。这样"在工程根目录 mvn spring-boot:run"和"在 target/ 下跑 jar"
 * 都能找到同一个文件。
 *
 * <p><b>优先级</b>：真实环境变量 / 命令行参数 <b>高于</b> .env，.env 高于 application.yml
 * 里的默认值（属性源被加到最后，即最低优先级）。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 属性源名字，便于排查"值到底从哪来"。 */
    public static final String SOURCE_NAME = "dotenvFile";

    /** 已加载的 .env 绝对路径；未找到该文件时此属性不存在。供启动日志提示用。 */
    public static final String LOADED_PATH_KEY = "dotenv.loaded.path";

    /** 显式指定 .env 位置：-Ddotenv.location=/path/to/.env。 */
    private static final String LOCATION_KEY = "dotenv.location";

    private static final String FILE_NAME = ".env";

    /** 从工作目录向上找的层数上限（防止在无关目录里翻到别人的 .env）。 */
    private static final int MAX_ASCENT = 5;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = locate(environment);
        if (file == null) {
            return;
        }
        Map<String, Object> values = parse(readLines(file));
        values.put(LOADED_PATH_KEY, file.toString());
        // 加到最后 = 最低优先级：不覆盖系统环境变量和 -D 参数
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, values));
    }

    @Override
    public int getOrder() {
        // 在 Spring 自己加载完 application.yml 之后再追加，确保是"最低优先级"
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static Path locate(ConfigurableEnvironment environment) {
        String explicit = environment.getProperty(LOCATION_KEY);
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit).toAbsolutePath();
            return Files.isRegularFile(p) ? p : null;
        }
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i <= MAX_ASCENT && dir != null; i++) {
            Path candidate = dir.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 .env 失败：" + file, e);
        }
    }

    /**
     * 解析 dotenv 文本为键值对（保持文件顺序）。
     *
     * <p>容错优先：无法识别的行跳过而不报错，避免一个手滑就启动不了。
     */
    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            out.put(key, parseValue(line.substring(eq + 1)));
        }
        return out;
    }

    /** 去引号 / 去行尾注释。未加引号时，{@code " #"} 之后视为注释。 */
    private static String parseValue(String rawValue) {
        String v = rawValue.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if (first == '"' && last == '"') {
                return unescapeDoubleQuoted(v.substring(1, v.length() - 1));
            }
            if (first == '\'' && last == '\'') {
                return v.substring(1, v.length() - 1);
            }
        }
        int comment = v.indexOf(" #");
        if (comment >= 0) {
            v = v.substring(0, comment).trim();
        }
        return v;
    }

    /** 双引号内支持 \n \t \r \" \\ 转义；其它未知转义原样保留。 */
    private static String unescapeDoubleQuoted(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }
}