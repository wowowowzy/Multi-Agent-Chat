-- AgentScope 多 Agent 群聊模拟系统 建表脚本（幂等，启动时执行）
-- 单文件 SQLite（WAL）。vec0 虚拟表因依赖扩展，在 VectorStore 初始化时按需创建。
--
-- 注意：本文件只用 CREATE TABLE IF NOT EXISTS，对**已存在的旧库不会补列**。
-- 旧库的补列/回填统一由 SchemaInitializer 的 migrate() 负责（ALTER TABLE ADD COLUMN）。

-- 群聊（E1 多群）。is_sample=1 的群是示例群，不允许解散。
CREATE TABLE IF NOT EXISTS groups (
    id         TEXT PRIMARY KEY,
    name       TEXT    NOT NULL,
    avatar     TEXT,
    notice     TEXT,                            -- 群公告 / 简介，注入成员视野
    is_sample  INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL
);

-- 群成员：人设（agents）是全局共享的，一个人设可以同时待在多个群里。
CREATE TABLE IF NOT EXISTS group_members (
    group_id  TEXT NOT NULL,
    agent_id  TEXT NOT NULL,
    joined_at TEXT NOT NULL,
    PRIMARY KEY (group_id, agent_id)
);
CREATE INDEX IF NOT EXISTS idx_group_members_agent ON group_members (agent_id);

CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id    TEXT    NOT NULL DEFAULT 'default',
    sender_id   TEXT    NOT NULL,
    sender_type TEXT    NOT NULL,               -- HUMAN / AGENT / SYSTEM
    sender_name TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    recalled    INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_group_created ON messages (group_id, created_at);

CREATE TABLE IF NOT EXISTS agents (
    id              TEXT PRIMARY KEY,
    name            TEXT    NOT NULL,
    avatar          TEXT,
    persona         TEXT,
    verbosity       INTEGER NOT NULL DEFAULT 5,
    emoji_tendency  INTEGER NOT NULL DEFAULT 5,
    response_speed  INTEGER NOT NULL DEFAULT 5,
    eagerness_bias  INTEGER NOT NULL DEFAULT 5,
    relationships   TEXT,                       -- JSON
    active          INTEGER NOT NULL DEFAULT 1, -- 逻辑删除标记
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL
);

-- token 流水：group_id 用于"按群聊划分统计"（旧库由 migrate() 补列并回填示例群）
CREATE TABLE IF NOT EXISTS token_usage (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    ts            TEXT    NOT NULL,
    group_id      TEXT,
    agent_id      TEXT,
    purpose       TEXT    NOT NULL,             -- GATE / SPEAK / MEMORY / EMBED
    input_tokens  INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_token_usage_ts ON token_usage (ts);
-- 注意：token_usage(group_id) 的索引**不能**写在这里：老库的 token_usage 还没有 group_id 列，
-- 而 schema.sql 先于 migrate() 执行，建索引会直接报 "no such column"。
-- 该索引由 SchemaInitializer.migrate() 在补列之后创建。

-- 群共享事实：按群隔离（每个群有自己的共识）
CREATE TABLE IF NOT EXISTS shared_facts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id   TEXT    NOT NULL DEFAULT 'default',
    fact       TEXT    NOT NULL,
    embedding  BLOB,
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_shared_facts_group_created ON shared_facts (group_id, created_at);

-- Agent 个人印象：按人设隔离、跨群共享。
-- 同一个"人"在两个群里，本来就是同一个人的记忆，所以这里不加 group_id。
CREATE TABLE IF NOT EXISTS agent_impressions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id   TEXT    NOT NULL,
    about_user TEXT,
    impression TEXT    NOT NULL,
    embedding  BLOB,
    created_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_impressions_agent_created ON agent_impressions (agent_id, created_at);

CREATE TABLE IF NOT EXISTS decision_logs (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id    INTEGER,                      -- 触发本轮决策的消息 id
    agent_id      TEXT    NOT NULL,
    want_to_speak INTEGER NOT NULL DEFAULT 0,
    eagerness     INTEGER NOT NULL DEFAULT 0,
    gist          TEXT,
    outcome       TEXT    NOT NULL,             -- SELECTED / SKIPPED / SILENT
    reason        TEXT,
    created_at    TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_decision_message ON decision_logs (message_id);

-- 每个群一个"停止聊天"开关
CREATE TABLE IF NOT EXISTS chat_state (
    group_id   TEXT PRIMARY KEY,
    stopped    INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT    NOT NULL
);

-- 每个群一份记忆提取进度
CREATE TABLE IF NOT EXISTS memory_state (
    group_id                TEXT PRIMARY KEY,
    last_extract_message_id INTEGER NOT NULL DEFAULT 0,
    last_extract_at         TEXT,
    updated_at              TEXT NOT NULL
);