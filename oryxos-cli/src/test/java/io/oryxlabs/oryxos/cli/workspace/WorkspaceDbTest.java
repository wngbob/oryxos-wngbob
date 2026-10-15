package io.oryxlabs.oryxos.cli.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceDb#execute} 的守点（2026-10-15 补强② 新增）。
 *
 * <p><b>为什么本类必须存在</b>：{@code WorkspaceDb} 交付时只有 {@code query}，<b>没有任何写方法</b>——轻命令此前全是只读的。
 * 补上写入面后，「写进去的能不能原样读回来」就是那条路径的地基，而地基出问题会在很远的地方现形（渠道名多一个引号、 配置里的中文变成问号），那时已经很难归因。
 *
 * <p><b>用一张本类自建的表，不碰 {@code notify_channels}</b>：本类验的是「带参语句执行」这个通用能力， 与任何业务表无关；用业务表会把两件事的失败混在一起。
 *
 * <p><b>最要紧的一条是「值里的引号原样进出」</b>：它证明走的是 {@code PreparedStatement} 绑定而不是字符串拼接—— 拼接的实现在这条断言上必然失败。
 */
class WorkspaceDbTest {

  /** 一行的标签：单引号、双引号、反斜杠、换行、中文全占上。 */
  private static final String NASTY_LABEL = "it's \"quoted\"\\ \n 中文标签";

  @TempDir Path tempDir;

  private WorkspaceDb open() {
    WorkspaceDb database = WorkspaceDb.open(tempDir.resolve("probe.db"));
    database.execute("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, label TEXT)");
    return database;
  }

  private static List<String> labels(WorkspaceDb database) {
    return database.query("SELECT label FROM probe ORDER BY id", WorkspaceDbTest::label);
  }

  private static String label(ResultSet resultSet) throws SQLException {
    return resultSet.getString("label");
  }

  @Test
  @DisplayName("写进去的能原样读回来 —— 单引号 / 双引号 / 反斜杠 / 换行 / 中文逐字一致")
  void valuesRoundTripVerbatim() {
    try (WorkspaceDb database = open()) {
      database.execute("INSERT INTO probe (id, label) VALUES (?, ?)", 1, NASTY_LABEL);

      assertThat(labels(database)).containsExactly(NASTY_LABEL);
    }
  }

  @Test
  @DisplayName("返回受影响行数：INSERT 为 1，DELETE 命中为 1、未命中为 0")
  void returnsAffectedRowCount() {
    try (WorkspaceDb database = open()) {
      assertThat(database.execute("INSERT INTO probe (id, label) VALUES (?, ?)", 1, "one"))
          .isEqualTo(1);
      assertThat(database.execute("DELETE FROM probe WHERE id = ?", 1)).isEqualTo(1);
      assertThat(database.execute("DELETE FROM probe WHERE id = ?", 1)).isZero();
    }
  }

  @Test
  @DisplayName("参数按 ? 出现顺序绑定，错位会被这条抓住")
  void parametersBindByPosition() {
    try (WorkspaceDb database = open()) {
      database.execute("INSERT INTO probe (id, label) VALUES (?, ?)", 7, "seven");
      database.execute("INSERT INTO probe (id, label) VALUES (?, ?)", 3, "three");

      assertThat(database.query("SELECT id FROM probe ORDER BY id", rs -> rs.getInt("id")))
          .containsExactly(3, 7);
    }
  }

  @Test
  @DisplayName("没有参数时也能执行（DDL / 全表删除）")
  void worksWithoutParameters() {
    try (WorkspaceDb database = open()) {
      database.execute("INSERT INTO probe (id, label) VALUES (1, 'x')");

      assertThat(database.execute("DELETE FROM probe")).isEqualTo(1);
      assertThat(labels(database)).isEmpty();
    }
  }

  @Test
  @DisplayName("同一条语句连续执行两次带不同参数，互不串值 —— 语句没有被复用成有状态的")
  void repeatedExecutionDoesNotLeakPreviousParameters() {
    try (WorkspaceDb database = open()) {
      database.execute("INSERT INTO probe (id, label) VALUES (?, ?)", 1, "first");
      database.execute("INSERT INTO probe (id, label) VALUES (?, ?)", 2, "second");

      assertThat(labels(database)).containsExactly("first", "second");
    }
  }
}
