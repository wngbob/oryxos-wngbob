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
