package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.oryxlabs.oryxos.core.engine.ToolExecutor;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 定时链路的真·集成验收（第 28 节「链路验」）。
 *
 * <p><b>它把「到点自己跑完查天气 → 推送」固化成可重演的测试</b>：真 DeepSeek、真 {@code http_get}、真 {@code notify}、IT
 * 内起的接收端把消息收下来，然后逐表对账：定时会话被复用、{@code llm_calls} / {@code tool_invocations} 不多不少、webhook
 * 真收到。失败路径单独一条：白名单外的渠道被 Sandbox 拦下、留 {@code success=false}、调度器照常接受下一次触发。
 *
 * <p><b>默认不跑</b>：{@code @Tag("integration")} + 环境变量闸门。手动跑法：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx ./mvnw -pl oryxos-cli -am test \
 *     -DexcludedGroups=none -Dtest=SchedulerFlowIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>它会真花钱、真出网</b>：两轮真实 ReAct（每轮 2 次模型调用）。天气源是 open-meteo（免 key）。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect",
      "oryxos.providers[0].name=deepseek",
      "oryxos.providers[0].api-key=${DEEPSEEK_API_KEY}"
    })
class SchedulerFlowIT {

  private static final String AGENT = "weather-notify";

  private static final String TASK_ID = AGENT + "#1";

  private static final String SCHEDULE_SESSION = "scheduler:scheduler:" + AGENT;

  /** 正常渠道的注册名；URL 在静态初始化块里指向 IT 内起的接收端。 */
  private static final String GOOD_CHANNEL = "weather-test";

  /** 故意配到白名单外的渠道：只用来验 Sandbox 拦截与失败落账。 */
  private static final String BLOCKED_CHANNEL = "blocked";

  private static final String WEATHER_URL =
      "https://api.open-meteo.com/v1/forecast?latitude=39.9042&longitude=116.4074&current=temperature_2m";

  private static final String MESSAGE =
      "请完成两件事，每一轮都必须重新执行、不要复用历史结果："
          + "① 调用 http_get 访问 "
          + WEATHER_URL
          + " 查询北京当前气温；"
          + "② 调用 notify，channel 参数传 "
          + GOOD_CHANNEL
          + "，content 参数写一句包含当前气温的穿搭建议。";

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  /** 接收端收到的原始请求体（每收到一次加一条）。 */
  private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

  private static final HttpServer WEBHOOK = startWebhook();

  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-sched-it-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(AGENT));
      Files.writeString(agentDir.resolve("AGENT.md"), agentMd(), StandardCharsets.UTF_8);
      applySchema();
      insertChannel(GOOD_CHANNEL, "webhook", webhookUrl());
      insertChannel(BLOCKED_CHANNEL, "webhook", "https://evil.example.com/hook");
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备定时链路 IT 临时工作区失败：" + ROOT, e);
    }
  }

  @Autowired private TestRestTemplate rest;

  @Autowired private AgentScheduler agentScheduler;

  @Autowired private ToolExecutor toolExecutor;

  @DynamicPropertySource
  static void tempWorkspaceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
    // 天气源与 IT 内的接收端都要进白名单；整表替换而不是追加下标（Binder 会因多余元素起不来）
    registry.add("http.allowed_domains", () -> List.of("api.open-meteo.com", "127.0.0.1"));
  }

  @AfterAll
  static void tearDown() {
    WEBHOOK.stop(0);
    System.clearProperty("oryxos.root");
    deleteRecursively(ROOT);
  }

  @Test
  @DisplayName("到点自己跑完「查天气 → notify 推送」：会话复用、账目不多不少、webhook 真收到；白名单外被拦且调度器不死")
  void scheduledRunEndToEnd() throws SQLException {
    agentScheduler.registerAll();
    assertThat(taskOf(TASK_ID).path("runCount").asInt()).isZero();

    // 第一次触发：真模型、真工具、真推送
    assertThat(agentScheduler.runNow(TASK_ID)).isTrue();
    assertThat(taskOf(TASK_ID).path("lastStatus").asText()).isEqualTo("success");
    assertThat(taskOf(TASK_ID).path("runCount").asInt()).isEqualTo(1);
    assertThat(count("select count(*) from sessions where session_id = ?", SCHEDULE_SESSION))
        .as("定时会话先立起来")
        .isEqualTo(1);
    assertThat(RECEIVED).as("webhook 应收到一次推送").hasSize(1);
    assertThat(RECEIVED.get(0)).contains("\"content\"").doesNotContain("evil");

    // 逐表对账：一轮恰好两次模型调用、两次工具调用（http_get + notify），都成功
    // 一轮 ReAct 是 3 次模型调用：①决定调 http_get ②拿到天气后决定调 notify ③拿到推送结果后组织
    // 最终答复。课件 2.1 写的「两条」只在 mock 的单工具脚本下成立——真链路里 notify 的 content 依赖
    // http_get 的结果，两个工具结构上无法并排调用（2026-10-20 真模型实测 3，报告里如实记录）。
    assertThat(count("select count(*) from llm_calls where session_id = ?", SCHEDULE_SESSION))
        .as("一轮 ReAct 三次模型调用")
        .isEqualTo(3);
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ? and success = 1",
                SCHEDULE_SESSION))
        .as("http_get + notify 各一次，都成功")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ? and tool_name = 'http_get'"
                    + " and success = 1",
                SCHEDULE_SESSION))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ? and tool_name = 'notify'"
                    + " and success = 1",
                SCHEDULE_SESSION))
        .isEqualTo(1);

    // 第二次触发：会话被复用（一条行、历史更长），任务计数与执行历史各加一
    assertThat(agentScheduler.runNow(TASK_ID)).isTrue();
    assertThat(count("select count(*) from sessions where session_id = ?", SCHEDULE_SESSION))
        .as("两次触发后仍是一条定时会话")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from task_executions where task_id = ? and success = 1", TASK_ID))
        .isEqualTo(2);
    assertThat(taskOf(TASK_ID).path("runCount").asInt()).isEqualTo(2);
    assertThat(RECEIVED).as("两次触发两笔推送").hasSize(2);

    // 失败路径：白名单外渠道在 IO 之前被 Sandbox 拦下，审计留 success=0
    ToolResult blocked =
        toolExecutor.execute(
            "scheduler-blocked",
            new AssistantMessage.ToolCall(
                "blocked-notify",
                "function",
                "notify",
                "{\"content\":\"这条推不出去\",\"channel\":\"" + BLOCKED_CHANNEL + "\"}"));
    assertThat(blocked.success()).isFalse();
    assertThat(blocked.errorMessage()).contains("白名单");
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = 'scheduler-blocked'"
                    + " and tool_name = 'notify' and success = 0"))
        .isEqualTo(1);
    assertThat(RECEIVED).as("被拦下的推送 MUST NOT 到达接收端").hasSize(2);

    // 调度器没被拖死：失败之后下一次触发照常执行
    assertThat(agentScheduler.runNow(TASK_ID)).isTrue();
    assertThat(taskOf(TASK_ID).path("lastStatus").asText()).isEqualTo("success");
    assertThat(taskOf(TASK_ID).path("runCount").asInt()).isEqualTo(3);
    assertThat(RECEIVED).hasSize(3);
  }

  private JsonNode taskOf(String taskId) {
    JsonNode tasks =
        Objects.requireNonNull(rest.getForEntity("/api/v1/schedules", JsonNode.class).getBody())
            .path("data");
    for (JsonNode node : tasks) {
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

  private static HttpServer startWebhook() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/hook",
          exchange -> {
            RECEIVED.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("起测试 webhook 接收端失败", e);
    }
  }

  private static String webhookUrl() {
    return "http://127.0.0.1:" + WEBHOOK.getAddress().getPort() + "/hook";
  }

  private static void insertChannel(String name, String type, String url) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO notify_channels (name, type, url, config_json, description)"
                    + " VALUES (?, ?, ?, '{}', 'SchedulerFlowIT')")) {
      statement.setString(1, name);
      statement.setString(2, type);
      statement.setString(3, url);
      statement.executeUpdate();
    }
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            SchedulerFlowIT.class.getResourceAsStream(SCHEMA_SCRIPT),
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
        description: 定时查天气并推送穿搭建议
        identity:
          agent_name: 天气播报员
          prompt: |
            你是一个准点播报天气的助手。每一轮都必须重新调用工具，不要复用历史里的天气数据。
            先用 http_get 查天气，再用 notify 推送一句穿搭建议。
        provider:
          name: deepseek
          model: deepseek-chat
        tools:
          - http_get
          - notify
        schedules:
          - cron: "0 0 9 1 1 *"
            zone: Asia/Shanghai
            message: "%s"
        ---

        到点干活。
        """
        .formatted(MESSAGE.replace("\"", "'"));
  }
}
