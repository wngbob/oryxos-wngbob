package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 多 Agent 的定时隔离（第 28 节「多 Agent 验」第三条边界）。
 *
 * <p><b>为什么单独一卷并换掉 {@code AgentService}</b>：「A 的定时任务抛异常，B 的下一个触发点照常执行」这条判据
 * 必须由测试决定成败，才能稳定复现。真实链路里让某一次固定炸掉要么靠坏 key（B 也会一起炸）、要么靠网络故障 （不可控），都会把「调度器是否隔离」这条判据变成碰运气。这里把 {@code
 * AgentService} 换成 mock：A 的消息必抛、 B 的消息必成，失败隔离就只剩 {@code AgentScheduler} 这一层变量——而它正是本节要判的东西。
 *
 * <p>守点：A 失败只污染 A 自己的 {@code last_status} / 执行历史，B 照常执行；A 再跑一次仍能进入执行入口 （调度器没被上一次失败拖死）。
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
class MultiAgentScheduleIsolationTest {

  private static final String AGENT_A = "sched-a";
  private static final String AGENT_B = "sched-b";
  private static final String TASK_A = AGENT_A + "#1";
  private static final String TASK_B = AGENT_B + "#1";
  private static final String MESSAGE_A = "A 的定时消息：这次一定失败";
  private static final String MESSAGE_B = "B 的定时消息：这次一定成功";
  private static final String BOOM = "A 的 Agent 炸了";

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path ROOT =
      Paths.get(
          System.getProperty("java.io.tmpdir"), "oryxos-sched-isolation-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      writeAgent(AGENT_A, MESSAGE_A);
      writeAgent(AGENT_B, MESSAGE_B);
      applySchema();
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备定时隔离临时工作区失败：" + ROOT, e);
    }
  }

  @Autowired private TestRestTemplate rest;

  @Autowired private AgentScheduler agentScheduler;

  @MockitoBean private AgentService agentService;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
  }

  @AfterAll
  static void clearRootOverride() {
    System.clearProperty("oryxos.root");
    deleteRecursively(ROOT);
  }

  @BeforeEach
  void stubAgents() {
    when(agentService.process(any(), eq(MESSAGE_A))).thenThrow(new IllegalStateException(BOOM));
    when(agentService.process(any(), eq(MESSAGE_B))).thenReturn("B 推送完成");
  }

  @Test
  @DisplayName("A 的定时抛异常只污染 A；B 的下一个触发点照常执行，调度器没被拖死")
  void oneAgentFailureDoesNotAffectTheOther() {
    agentScheduler.registerAll();
    assertThat(getData("/api/v1/schedules")).hasSize(2);

    // A 失败：状态与历史都记在 A 自己名下
    assertThat(agentScheduler.runNow(TASK_A)).isTrue();
    JsonNode afterA = taskOf(TASK_A);
    assertThat(afterA.path("lastStatus").asText()).isEqualTo("failed");
    assertThat(afterA.path("runCount").asInt()).isEqualTo(1);

    JsonNode executionsA = getData("/api/v1/schedules/{id}/executions", TASK_A);
    assertThat(executionsA).hasSize(1);
    assertThat(executionsA.get(0).path("success").asBoolean()).isFalse();
    assertThat(executionsA.get(0).path("errorMessage").asText()).isEqualTo(BOOM);

    // B 照常执行：既没继承 A 的失败，也没被 A 的异常挡下
    assertThat(agentScheduler.runNow(TASK_B)).isTrue();
    JsonNode afterB = taskOf(TASK_B);
    assertThat(afterB.path("lastStatus").asText()).isEqualTo("success");
    assertThat(afterB.path("runCount").asInt()).isEqualTo(1);
    assertThat(afterB.path("nextRunAt").asText()).isNotBlank();
    assertThat(getData("/api/v1/schedules/{id}/executions", TASK_B))
        .singleElement()
        .satisfies(execution -> assertThat(execution.path("success").asBoolean()).isTrue());

    // 调度器仍活着：A 再跑一次仍能进入执行入口并落第二条历史（不是「抛一次就永久卡死」）
    assertThat(agentScheduler.runNow(TASK_A)).isTrue();
    assertThat(taskOf(TASK_A).path("runCount").asInt()).isEqualTo(2);
    assertThat(getData("/api/v1/schedules/{id}/executions", TASK_A)).hasSize(2);
  }

  private JsonNode taskOf(String taskId) {
    for (JsonNode node : getData("/api/v1/schedules")) {
      if (taskId.equals(node.path("taskId").asText())) {
        return node;
      }
    }
    throw new AssertionError("任务列表里找不到 " + taskId);
  }

  private JsonNode getData(String path, Object... uriVariables) {
    ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class, uriVariables);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return Objects.requireNonNull(response.getBody()).path("data");
  }

  private static void writeAgent(String name, String message) throws IOException {
    Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(name));
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        """
        ---
        description: 定时隔离测试 Agent %s
        identity:
          agent_name: %s
          prompt: 你是定时隔离测试 Agent。
        provider:
          name: mock
          model: mock-model
        schedules:
          - cron: "0 0 9 1 1 *"
            zone: Asia/Shanghai
            message: "%s"
        ---

        到点干活。
        """
            .formatted(name, name, message),
        StandardCharsets.UTF_8);
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            MultiAgentScheduleIsolationTest.class.getResourceAsStream(SCHEMA_SCRIPT),
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
                  // 临时目录在 /tmp 下，清理失败由系统回收
                }
              });
    } catch (IOException ignored) {
      // 同上
    }
  }
}
