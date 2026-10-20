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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * 跨重启恢复的真·集成验收（第 28 节「重启验」）。
 *
 * <p><b>它真的重启一次</b>：同一份临时工作区 / 同一个 SQLite 文件上先后起两个 Spring 上下文，中间把第一个 关掉——不是「模拟重启」，而是把进程内的 Bean
 * 全部丢掉再重新装配一遍。重启后逐项核对：会话历史、长期记忆、 定时任务（状态 + 执行历史）、审计记录全部原样回来；重启后新发的消息继续往同一张审计表里追加，不断档。
 *
 * <p>四项里任何一项没回来，都说明有状态偷偷留在了进程内存里——这是走向分布式（实例无状态）前必须掐灭的隐患。
 *
 * <p><b>默认不跑</b>：{@code @Tag("integration")} + 环境变量闸门。手动跑法：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx ./mvnw -pl oryxos-cli -am test \
 *     -DexcludedGroups=none -Dtest=RestartRecoveryIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class RestartRecoveryIT {

  private static final String AGENT = "restart-agent";

  private static final String TASK_ID = AGENT + "#1";

  private static final String SCHEDULE_SESSION = "scheduler:scheduler:" + AGENT;

  private static final String WEB_SESSION = "web:default:" + AGENT;

  private static final String FACT = "记住：我的咖啡偏好是美式（重启测试专用）";
  private static final String FACT_2 = "记住：重启之后我还是喜欢美式";

  private static final String SCHEDULE_MESSAGE = "请调用 save_memory 把这句话记进长期记忆：定时任务跨重启测试";

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-restart-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(AGENT));
      Files.writeString(agentDir.resolve("AGENT.md"), agentMd(), StandardCharsets.UTF_8);
      applySchema();
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备重启 IT 临时工作区失败：" + ROOT, e);
    }
  }

  @AfterAll
  static void tearDown() {
    System.clearProperty("oryxos.root");
    deleteRecursively(ROOT);
  }

  @Test
  @DisplayName("kill 再起：会话 / 记忆 / 定时任务状态与历史 / 审计四样原样恢复，新消息继续写同一张表")
  void everythingSurvivesRestart() throws SQLException {
    int llmCallsBefore;
    int webMessagesBefore;

    // ── 第一个进程：对话 + 记忆 + 定时触发一次 ──────────────────────────────
    try (ConfigurableApplicationContext first = startContext()) {
      RestTemplate rest = restFor(first);
      post(rest, "/api/v1/sessions", Map.of("profile", AGENT));
      post(rest, "/api/v1/sessions/" + WEB_SESSION + "/messages", Map.of("content", FACT));
      webMessagesBefore = getData(rest, "/api/v1/sessions/" + WEB_SESSION).path("messages").size();
      assertThat(webMessagesBefore).isPositive();

      first.getBean(AgentScheduler.class).registerAll();
      assertThat(first.getBean(AgentScheduler.class).runNow(TASK_ID)).isTrue();
      assertThat(taskOf(rest, TASK_ID).path("lastStatus").asText()).isEqualTo("success");

      llmCallsBefore =
          count(
              "select count(*) from llm_calls where session_id in (?, ?)",
              WEB_SESSION,
              SCHEDULE_SESSION);
      assertThat(llmCallsBefore).as("两个会话各自都发生过真实模型调用").isEqualTo(4);
    }

    // ── 第二个进程：同一份工作区、同一个库，Bean 全部重新装配 ────────────────
    try (ConfigurableApplicationContext second = startContext()) {
      RestTemplate rest = restFor(second);

      // ① 会话回来了：重启前的完整历史
      JsonNode history = getData(rest, "/api/v1/sessions/" + WEB_SESSION).path("messages");
      assertThat(history).hasSize(webMessagesBefore);
      assertThat(history.findValuesAsText("content"))
          .anySatisfy(text -> assertThat(text).contains("美式"));

      // ② 记忆回来了：MEMORY.md 是文件，天生跨重启
      JsonNode memory = getData(rest, "/api/v1/memory");
      assertThat(memory.path("core").asText() + memory.path("archival").asText()).contains("美式");

      // ③ 定时任务回来了：定义重新登记，状态与历史仍在两张表里
      assertThat(taskOf(rest, TASK_ID).path("runCount").asInt()).isEqualTo(1);
      assertThat(taskOf(rest, TASK_ID).path("lastStatus").asText()).isEqualTo("success");
      assertThat(taskOf(rest, TASK_ID).path("nextRunAt").asText()).isNotBlank();
      JsonNode executions = getData(rest, "/api/v1/schedules/{id}/executions", TASK_ID);
      assertThat(executions).hasSize(1);
      assertThat(executions.get(0).path("success").asBoolean()).isTrue();

      // 重新登记 MUST NOT 冲掉运行历史（register 只动定义面）
      second.getBean(AgentScheduler.class).registerAll();
      assertThat(taskOf(rest, TASK_ID).path("runCount").asInt()).isEqualTo(1);
      assertThat(getData(rest, "/api/v1/schedules/{id}/executions", TASK_ID)).hasSize(1);

      // ④ 审计不断档：重启前两条会话的调用一条不少；重启后新消息接着往同一张表追加
      assertThat(
              count(
                  "select count(*) from llm_calls where session_id in (?, ?)",
                  WEB_SESSION,
                  SCHEDULE_SESSION))
          .isEqualTo(llmCallsBefore);
      post(rest, "/api/v1/sessions/" + WEB_SESSION + "/messages", Map.of("content", FACT_2));
      assertThat(count("select count(*) from llm_calls where session_id = ?", WEB_SESSION))
          .as("重启后新的一轮继续落同一张审计表")
          .isEqualTo(4);
      assertThat(getData(rest, "/api/v1/sessions/" + WEB_SESSION).path("messages").size())
          .isGreaterThan(webMessagesBefore);
    }
  }

  private static ConfigurableApplicationContext startContext() {
    Map<String, Object> properties = new HashMap<>();
    properties.put("server.port", "0");
    properties.put("spring.datasource.url", "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
    properties.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
    properties.put("spring.datasource.hikari.maximum-pool-size", "1");
    properties.put("spring.jpa.hibernate.ddl-auto", "none");
    properties.put(
        "spring.jpa.properties.hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
    properties.put("oryxos.providers[0].name", "deepseek");
    properties.put("oryxos.providers[0].api-key", "${DEEPSEEK_API_KEY}");
    return new SpringApplicationBuilder(CliApplication.class)
        .web(WebApplicationType.SERVLET)
        .properties(properties)
        .run();
  }

  private static RestTemplate restFor(ConfigurableApplicationContext context) {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    RestTemplate rest = new RestTemplate();
    rest.setUriTemplateHandler(new DefaultUriBuilderFactory("http://127.0.0.1:" + port));
    return rest;
  }

  private static JsonNode post(RestTemplate rest, String path, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode responseBody = Objects.requireNonNull(response.getBody());
    assertThat(responseBody.path("code").asInt()).isEqualTo(200);
    return responseBody.path("data");
  }

  private static JsonNode getData(RestTemplate rest, String path, Object... uriVariables) {
    ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class, uriVariables);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return Objects.requireNonNull(response.getBody()).path("data");
  }

  private static JsonNode taskOf(RestTemplate rest, String taskId) {
    for (JsonNode node : getData(rest, "/api/v1/schedules")) {
      if (taskId.equals(node.path("taskId").asText())) {
        return node;
      }
    }
    throw new AssertionError("任务列表里找不到 " + taskId);
  }

  private static int count(String sql, Object... args) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : -1;
      }
    }
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            RestartRecoveryIT.class.getResourceAsStream(SCHEMA_SCRIPT),
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

  private static String agentMd() {
    return """
        ---
        description: 跨重启恢复测试 Agent
        identity:
          agent_name: 重启测试员
          prompt: 用户给出值得长期记住的信息时，调用 save_memory 工具记下来，然后简短答复。
        provider:
          name: deepseek
          model: deepseek-chat
        tools:
          - save_memory
        schedules:
          - cron: "0 0 9 1 1 *"
            zone: Asia/Shanghai
            message: "%s"
        ---

        到点把该记的记进长期记忆。
        """
        .formatted(SCHEDULE_MESSAGE);
  }
}
