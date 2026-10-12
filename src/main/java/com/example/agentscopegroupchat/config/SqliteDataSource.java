package com.example.agentscopegroupchat.config;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

/**
 * 面向 SQLite 单文件的轻量 DataSource。
 *
 * <p>每次取连接时统一应用 PRAGMA（WAL / 外键 / busy_timeout / synchronous），
 * 并按需加载 sqlite-vec 原生扩展。连接创建成本极低，本量级无需连接池。
 */
public class SqliteDataSource implements DataSource {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(SqliteDataSource.class);

    private final String url;
    /** 原生扩展绝对路径；null 表示不加载（走纯 Java 兜底）。 */
    private final String extensionPath;

    /**
     * sqlite-vec 的入口符号。SQLite 默认按文件名推导入口点（vec0.dll → sqlite3_vec0_init），
     * 而 sqlite-vec 实际导出的是 {@code sqlite3_vec_init}，故必须显式指定，否则报
     * "找不到指定的程序"(ERROR_PROC_NOT_FOUND)。
     */
    private static final String VEC_ENTRY_POINT = "sqlite3_vec_init";

    private volatile boolean extensionAvailable;
    /** 一旦加载失败就置位，避免每条连接都重试并刷日志。 */
    private volatile boolean extensionDisabled;
    private PrintWriter logWriter;
    private int loginTimeout;

    public SqliteDataSource(String dbPath, String extensionPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        this.extensionPath = extensionPath;
    }

    public boolean isExtensionAvailable() {
        return extensionAvailable;
    }

    @Override
    public Connection getConnection() throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.enforceForeignKeys(true);
        cfg.setBusyTimeout(8000);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        boolean loadExt = extensionPath != null && !extensionDisabled;
        cfg.enableLoadExtension(loadExt);

        Connection conn = cfg.createConnection(url);
        if (loadExt) {
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT load_extension('" + escape(extensionPath) + "', '" + VEC_ENTRY_POINT + "')");
                extensionAvailable = true;
            } catch (SQLException e) {
                extensionAvailable = false;
                extensionDisabled = true; // 降级后不再重试，避免日志刷屏
                log.warn("sqlite-vec 扩展加载失败，将降级为纯 Java 余弦兜底：{}", e.getMessage());
            }
        }
        return conn;
    }

    private static String escape(String path) {
        // sqlite load_extension 使用正斜杠更稳妥，并对单引号做转义
        return path.replace('\\', '/').replace("'", "''");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        throw new SQLException("无法 unwrap 到 " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}