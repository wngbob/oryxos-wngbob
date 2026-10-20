package io.oryxlabs.oryxos.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code oryxos init} 与库结构补齐的集成守点（2026-10-20 用户批准新增）。
 *
 * <p><b>为什么不能只测 {@code SchemaDrift}</b>：补列逻辑对、但 {@code init} 忘了调用它、或者调用顺序反了（先建表脚本遇到依赖缺失列的索引
 * 会失败），用户在命令行上看到的仍是老样子。本类从命令入口跑，钉的是「这条路径真的被接上了」。
 */
class InitCommandTest {

  @TempDir Path tempDir;

  private static List<String> columns(Workspace workspace, String table) {
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      return database.query("PRAGMA table_info(" + table + ")", rs -> rs.getString("name"));
    }
  }

  private static Workspace legacyWorkspace(Path tempDir) throws IOException {
    Files.createDirectories(tempDir.resolve(".oryxos"));
    Workspace workspace = new Workspace(tempDir);
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      database.execute(
          "CREATE TABLE llm_calls (id INTEGER PRIMARY KEY AUTOINCREMENT, provider VARCHAR(64))");
      database.execute("INSERT INTO llm_calls (id, provider) VALUES (1, 'deepseek')");
    }
    return workspace;
  }

  @Test
  @DisplayName("旧工作区跑 init：缺的列补上、原数据还在、输出里如实打出来")
  void initMigratesLegacyDatabaseAndReportsIt() throws IOException {
    Workspace workspace = legacyWorkspace(tempDir);

    String output = CommandTestSupport.run(new CommandLine(new InitCommand(workspace)));

    assertThat(output).contains("数据库表结构").contains("补列: llm_calls.success");
    assertThat(columns(workspace, "llm_calls"))
        .contains("success", "error_message", "provider", "session_id");
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      assertThat(
              database.query("SELECT provider FROM llm_calls WHERE id = 1", rs -> rs.getString(1)))
          .containsExactly("deepseek");
    }
  }

  @Test
  @DisplayName("全新工作区跑 init：无漂移，五张表齐，输出说明无需补列")
  void initCreatesFreshSchemaWithoutDrift() {
    Workspace workspace = new Workspace(tempDir);

    String output = CommandTestSupport.run(new CommandLine(new InitCommand(workspace)));

    assertThat(output).contains("与 db/schema.sql 一致");
    assertThat(columns(workspace, "sessions")).contains("session_id", "messages_json");
    assertThat(columns(workspace, "llm_calls")).contains("success", "error_message");
    assertThat(columns(workspace, "memory_entries")).contains("scope", "content");
  }
}
