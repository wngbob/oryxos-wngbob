-- OryxOS SQLite 建表脚本（手工维护）
--
-- 为什么不走 ddl-auto：SQLite 的 ALTER TABLE 能力弱，hibernate.ddl-auto=update
-- 对表结构演进的支持很弱，表结构演进必须走手工脚本（CLAUDE.md「SQLite 核心表」工程提示）。
-- 因此 oryxos-boot 的 application.properties 里 ddl-auto=none，表一律由本脚本创建。
--
-- 字段口径来源：需求文档「核心数据模型」的 Tool Invocation / LLM Call 两节。
-- 索引：技术方案未定义索引，此处不预先添加；待真实查询模式明确后按需补。
--
-- 执行方式：在仓库根目录执行
--   sqlite3 .oryxos/oryxos.db < oryxos-storage/src/main/resources/db/schema.sql
-- 或由后续 `oryxos init` 命令内嵌执行（US 落地时接入）。

-- 每次 Tool 调用记录（审计表，Day One 写入）
CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    VARCHAR(128),
    tool_name     VARCHAR(128),
    input_json    TEXT,
    result_json   TEXT,
    success       BOOLEAN,
    error_message TEXT,
    duration_ms   BIGINT,
    created_at    TIMESTAMP
);

-- 每次 LLM 调用记录（审计表，Day One 写入）
CREATE TABLE IF NOT EXISTS llm_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        VARCHAR(128),
    provider          VARCHAR(64),
    model             VARCHAR(128),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    duration_ms       BIGINT,
    success           BOOLEAN,
    error_message     TEXT,
    created_at        TIMESTAMP
);

-- 会话表：对话历史的持久化形态（第 18 节新建）
-- 列名与长度逐字照 000-runtime-kernel/data-model.md A1，不新增列、不改类型。
-- session_id 由 channel:user:profile 三段拼接生成，不是自增，故不用 AUTOINCREMENT。
-- messages_json 一列存整个历史，核心阶段不按条拆表。
-- 注意：本文件里任何注释都 MUST NOT 含分号字符——init 与测试都是按分号切分脚本再逐条执行的，
-- 注释里出现分号会切出一条「只有注释、没有语句」的碎片，驱动执行它之后底层句柄即失效，
-- 下一条真语句直接报 "The prepared statement has been finalized"。
CREATE TABLE IF NOT EXISTS sessions (
    session_id     VARCHAR(128) PRIMARY KEY,
    profile_name   VARCHAR(128),
    channel        VARCHAR(32),
    user_id        VARCHAR(128),
    messages_json  TEXT,
    status         VARCHAR(16),
    created_at     TIMESTAMP,
    last_active_at TIMESTAMP,
    archived_at    TIMESTAMP
);

-- 通知渠道全局注册表（第 19 节新建）
-- 列名与语义逐字照 000-runtime-kernel/data-model.md A6，不新增列。
-- 渠道是全局的，不属于任何 Agent——按注册名引用，webhook 地址不进对话内容。
-- name 是人指定的注册名，是业务键，故不用 AUTOINCREMENT。
-- url 含 token，属敏感串，MUST NOT 出现在对话 / 日志 / 报错 / 命令输出中。
-- config_json 形状为「JSON 对象、值均为字符串」，解析与校验归消费方。
-- 写入面自 2026-10-15 起由 CLI 三条轻命令提供（notify-channel add|list|remove，走原生 JDBC 绕过 Spring Data），
-- 故运行期本表不再恒为空。REST CRUD 与管理台仍归第 26 节。
CREATE TABLE IF NOT EXISTS notify_channels (
    name        VARCHAR(128) PRIMARY KEY,
    type        VARCHAR(32),
    url         VARCHAR(1024),
    config_json TEXT,
    description VARCHAR(512)
);

-- 长期记忆条目（第 22 节新建，SQLite 档的持久化形态）
-- 列名与语义逐字照 000-runtime-kernel/data-model.md 的 MemoryEntry，不新增列。
-- 与 sessions / 审计表同口径:手工建表脚本维护,不依赖 hibernate.ddl-auto。
-- scope 是分区(CORE 永远全量注入不参与检索 / ARCHIVAL 可截断可检索),写入时由调用方显式指定。
-- 本表是全局单份,不按 Agent 隔离,故不与 agents / sessions 建任何外键。
-- 归档区的截断在查询层用 LIMIT 实现(最近 100 条),核心区查询 MUST NOT 带 LIMIT。
CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- 按分区取数的索引:三处查询(核心区升序全量 / 归档区降序取最近 N 条 / 归档区 LIKE 检索)全部先按 scope 过滤。
CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
