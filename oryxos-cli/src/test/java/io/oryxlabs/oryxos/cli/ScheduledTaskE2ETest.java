package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 定时任务子系统全流程（第 28 节「主验」，mock 驱动、gate 内无 key）。
 *
 * <p>把「定义 → 登记 → 执行 → 持久化 → 管理」一次跑通：临时工作区的 {@code AGENT.md} 声明一条远期 cron（靠「立即执行」触发、不等时间），启动即登记进
 * {@code scheduled_tasks}；POST run 走真实 ReAct（mock 脚本调一次 {@code save_memory}）；对账 {@code run_count} /
 * {@code last_status} / 执行历史 / 记忆，再 PUT 停用。
 *
 * <p><b>hermetic 手段与 {@code MockAgentE2ETest} 相同</b>：{@code oryxos.root} + 同一个临时目录下的
 * SQLite，静态初始化块里备好 Agent 与建表脚本、类结束后整体删除。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect",
      "oryxos.providers[0].name=mock",
      "oryxos.providers[0].api-key=unused",
      "oryxos.providers[1].name=deepseek",
      "oryxos.providers[1].api-key=unused"
    })
class ScheduledTaskE2ETest {

  private static final String AGENT = "daily-weather";

  /** 与 {@code AgentScheduler} 的派生规则一致：{@code <Profile 名>#<序号>}。 */
  private static final String TASK_ID = AGENT + "#1";

  /** 本期消息：mock 会把它原样交给 {@code save_memory}，故可用它反查记忆写入。 */
  private static final String MESSAGE = "播报今天的天气：北京晴，26 度";

  private static final String SCHEDULE_SESSION = "scheduler:scheduler:" + AGENT;

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-schedule-e2e-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(AGENT));
      Files.writeString(agentDir.resolve("AGENT.md"), agentMd(), StandardCharsets.UTF_8);
      applySchema();
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备定时任务 E2E 临时工作区失败：" + ROOT, e);
    }
  }

  @Autowired private TestRestTemplate rest;

  @Autowired private AgentScheduler agentScheduler;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
  }

  @AfterAll
  static void clearRootOverride() {
    System.clearProperty("oryxos.root");
    deleteRecursively(ROOT);
  }

  @Test
  @DisplayName("登记 → 立即执行 → 状态与历史落库 → 记忆可查 → 停用")
  void scheduledTaskLifecycleOverHttp() throws SQLException, IOException {
    agentScheduler.registerAll();

    // ② 启动即登记：一条任务、未跑过、启用、下次触发在未来
    JsonNode listed = getData("/api/v1/schedules");
    assertThat(listed).hasSize(1);
    JsonNode task = listed.get(0);
    assertThat(task.path("taskId").asText()).isEqualTo(TASK_ID);
    assertThat(task.path("profileName").asText()).isEqualTo(AGENT);
    assertThat(task.path("enabled").asBoolean()).isTrue();
    assertThat(task.path("runCount").asInt()).isZero();
    assertThat(task.path("lastStatus").isNull()).isTrue();
    assertThat(task.path("nextRunAt").asText()).isNotBlank();

    // ③ 立即执行：同步跑完，返回体已是执行后的状态
    JsonNode ran = postData("/api/v1/schedules/{id}/run", Map.of(), TASK_ID);
    assertThat(ran.path("taskId").asText()).isEqualTo(TASK_ID);
    assertThat(ran.path("runCount").asInt()).isEqualTo(1);
    assertThat(ran.path("lastStatus").asText()).isEqualTo("success");
    assertThat(ran.path("lastRunAt").asText()).isNotBlank();

    // ④ 持久化：列表 / 执行历史 / 记忆三面一致
    JsonNode afterRun = getData("/api/v1/schedules").get(0);
    assertThat(afterRun.path("runCount").asInt()).isEqualTo(1);
    assertThat(afterRun.path("lastStatus").asText()).isEqualTo("success");

    JsonNode executions = getData("/api/v1/schedules/{id}/executions", TASK_ID);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).path("success").asBoolean()).isTrue();
    assertThat(executions.get(0).path("sessionId").asText()).isEqualTo(SCHEDULE_SESSION);
    assertThat(executions.get(0).path("errorMessage").isNull()).isTrue();

    // 记忆自第 30 节起按 Agent 隔离（agents/<name>/MEMORY.md），全局 GET /api/v1/memory 同时移除：
    // 这里直接看磁盘——钟推跑在 Agent 上下文里，记忆必须落进该 Agent 自己那一份，而不是全局单份
    String memoryFile =
        Files.readString(
            ROOT.resolve("agents").resolve(AGENT).resolve("MEMORY.md"), StandardCharsets.UTF_8);
    assertThat(memoryFile).contains(MESSAGE);

    // 字段级对账：任务行状态滚对、历史行一条成功、会话复用同一身份
    assertThat(
            count(
                "select count(*) from scheduled_tasks where task_id = ? and run_count = 1"
                    + " and last_status = 'success' and enabled = 1",
                TASK_ID))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from task_executions where task_id = ? and success = 1", TASK_ID))
        .isEqualTo(1);
    assertThat(count("select count(*) from sessions where session_id = ?", SCHEDULE_SESSION))
        .isEqualTo(1);

    // ⑤ 停用：列表显示已停用；run_count 与历史不受影响
    JsonNode disabled = putData("/api/v1/schedules/{id}", Map.of("enabled", false), TASK_ID);
    assertThat(disabled.path("enabled").asBoolean()).isFalse();
    assertThat(disabled.path("runCount").asInt()).isEqualTo(1);
    assertThat(getData("/api/v1/schedules").get(0).path("enabled").asBoolean()).isFalse();
  }

  private JsonNode getData(String path, Object... uriVariables) {
    ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class, uriVariables);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = Objects.requireNonNull(response.getBody());
    assertThat(body.path("code").asInt()).isEqualTo(200);
    return body.path("data");
  }

  private JsonNode postData(String path, Map<String, ?> body, Object... uriVariables) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class, uriVariables);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode responseBody = Objects.requireNonNull(response.getBody());
    assertThat(responseBody.path("code").asInt()).isEqualTo(200);
    return responseBody.path("data");
  }

  private JsonNode putData(String path, Map<String, ?> body, Object... uriVariables) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        rest.exchange(
            path, HttpMethod.PUT, new HttpEntity<>(body, headers), JsonNode.class, uriVariables);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode responseBody = Objects.requireNonNull(response.getBody());
    assertThat(responseBody.path("code").asInt()).isEqualTo(200);
    return responseBody.path("data");
  }

  private static int count(String sql, String value) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, value);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : -1;
      }
    }
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            ScheduledTaskE2ETest.class.getResourceAsStream(SCHEMA_SCRIPT),
            "classpath 上找不到建表脚本 " + SCHEMA_SCRIPT)) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
        Statement statement = connection.createStatement()) {
      for (String sql : script.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  private static void deleteRecursively(Path path) {
    try (var walk = Files.walk(path)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              file -> {
                try {
                  Files.deleteIfExists(file);
                } catch (IOException ignored) {
                  // 清理失败不影响判卷；临时目录在 /tmp 下，由系统回收
                }
              });
    } catch (IOException ignored) {
      // 同上
    }
  }

  private static String agentMd() {
    return """
        ---
        description: 定时任务子系统 E2E Agent
        identity:
          agent_name: 天气播报员
          prompt: 你是 OryxOS 的定时播报助手。
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        schedules:
          - cron: "0 0 9 1 1 *"
            zone: Asia/Shanghai
            message: "%s"
        ---

        到点把播报内容记进长期记忆。
        """
        .formatted(MESSAGE);
  }
}
