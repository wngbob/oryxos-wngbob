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
-- 第 19 节只读——写入路径归第 26 节，故运行期本表恒为空是预期状态。
CREATE TABLE IF NOT EXISTS notify_channels (
    name        VARCHAR(128) PRIMARY KEY,
    type        VARCHAR(32),
    url         VARCHAR(1024),
    config_json TEXT,
    description VARCHAR(512)
);
