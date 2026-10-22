package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxlabs.oryxos.provider.MockChatModel;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 无 key 全链路，整台服务（第 27 节「验收 harness」）。
 *
 * <p><b>它与人手动点一遍完全一样，只是自动化了</b>：真起 SERVLET 容器、真 SQLite、真 Spring 装配， 从 HTTP 建会话、发对话，再把会话 / 记忆 / 工具 /
 * 审计全查回来。{@code MockProviderFlowTest} 判的是手工装配下的接线， 本类判的是<b>容器这一层</b>——扫描根、{@code @EntityScan}、Bean
 * 歧义、工作区根指向，单靠手工 {@code new} 都照不到。
 *
 * <p><b>hermetic 的两个手段</b>：① {@code oryxos.root} 系统属性把工作区根指向临时目录（默认仍是 {@code .oryxos}），
 * 测试不读也不写开发者机器上的真实工作区；② SQLite 指向同一个临时目录，类结束后连同工作区一起删除。
 * 两者都在<b>静态初始化块</b>里完成——{@code @SpringBootTest} 的上下文在 {@code @BeforeAll} 之前就刷新， 晚于它的任何「准备」都来不及。
 *
 * <p><b>进 gate、无 key</b>：provider 清单在测试属性里整体换成 {@code mock}（{@code application.yaml} 里 deepseek 的
 * {@code ${DEEPSEEK_API_KEY}} 占位也一并覆盖掉，故本机没配 key 照样跑）。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect",
      // 索引 0 / 1 逐项覆盖：随包配置里 0 是 deepseek（占位必须被覆盖，否则无 key 机器起不来）
      "oryxos.providers[0].name=mock",
      "oryxos.providers[0].api-key=unused",
      "oryxos.providers[1].name=deepseek",
      "oryxos.providers[1].api-key=unused"
    })
class MockAgentE2ETest {

  private static final String AGENT = "mock-agent";

  private static final String FACT = "记住：我喜欢喝美式咖啡";

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  /** 临时工作区根（{@code oryxos.root} 指的就是它，不再叠一层 {@code .oryxos}）。 */
  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-e2e-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(AGENT));
      Files.writeString(agentDir.resolve("AGENT.md"), agentMd(), StandardCharsets.UTF_8);
      applySchema();
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备 E2E 临时工作区失败：" + ROOT, e);
    }
  }

  @Autowired private TestRestTemplate rest;

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
  @DisplayName("HTTP 建会话、发「记住…」、再把会话 / 列表 / 记忆 / 工具 / 审计全查回来")
  void wholeChainOverHttp() throws SQLException, IOException {
    String sessionId = createSession();

    JsonNode sendBody =
        post("/api/v1/sessions/" + sessionId + "/messages", Map.of("content", FACT));
    assertThat(sendBody.path("data").path("reply").asText()).isEqualTo(MockChatModel.FINAL_REPLY);

    // 支柱一 · 对话答得出：历史四条按序齐备
    JsonNode history =
        Objects.requireNonNull(
                rest.getForEntity("/api/v1/sessions/" + sessionId, JsonNode.class).getBody())
            .path("data")
            .path("messages");
    assertThat(history).hasSize(4);
    assertThat(history.get(0).path("role").asText()).isEqualTo("user");
    assertThat(history.get(0).path("content").asText()).isEqualTo(FACT);
    assertThat(history.get(2).path("role").asText()).isEqualTo("tool");
    assertThat(history.get(3).path("content").asText()).isEqualTo(MockChatModel.FINAL_REPLY);

    // 会话列表（本节新增的端点，也是管理台「会话」页的数据源）
    JsonNode list =
        Objects.requireNonNull(rest.getForEntity("/api/v1/sessions", JsonNode.class).getBody())
            .path("data");
    JsonNode summary = null;
    for (JsonNode node : list) {
      if (sessionId.equals(node.path("sessionId").asText())) {
        summary = node;
      }
    }
    assertThat(summary).isNotNull();
    assertThat(summary.path("messageCount").asInt()).isEqualTo(4);
    assertThat(summary.path("profile").asText()).isEqualTo(AGENT);

    // 支柱二 · 记忆写得出：默认 markdown 档，真落了文件。
    // 第 30 节起 Markdown 档按 Agent 隔离（agents/<name>/MEMORY.md），全局 GET /api/v1/memory 同时移除；
    // per-agent 的 REST 面是 GET /api/v1/agents/{name}/memory，它的判卷在 web 层，这里只看「事真的落到磁盘了吗」
    String memoryFile =
        Files.readString(
            ROOT.resolve("agents").resolve(AGENT).resolve("MEMORY.md"), StandardCharsets.UTF_8);
    assertThat(memoryFile).contains("我喜欢喝美式咖啡");

    // 支柱三 · 工具查得到
    JsonNode tools =
        Objects.requireNonNull(rest.getForEntity("/api/v1/tools", JsonNode.class).getBody())
            .path("data");
    assertThat(tools.findValuesAsText("name")).contains("save_memory");

    // 对账：llm_calls 恰 2 条（两轮），tool_invocations 恰 1 条（那次 save_memory），不多不少
    assertThat(count("select count(*) from llm_calls where session_id = '" + sessionId + "'"))
        .isEqualTo(2);
    assertThat(
            count("select count(*) from tool_invocations where session_id = '" + sessionId + "'"))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = '"
                    + sessionId
                    + "' and tool_name = 'save_memory' and success = 1"))
        .isEqualTo(1);
  }

  private String createSession() {
    JsonNode created = post("/api/v1/sessions", Map.of("profile", AGENT));
    String sessionId = created.path("data").path("sessionId").asText();
    assertThat(sessionId).isEqualTo("web:default:" + AGENT);
    return sessionId;
  }

  private JsonNode post(String path, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode responseBody = Objects.requireNonNull(response.getBody());
    assertThat(responseBody.path("code").asInt()).isEqualTo(200);
    return responseBody;
  }

  private static int count(String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      return resultSet.next() ? resultSet.getInt(1) : -1;
    }
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            MockAgentE2ETest.class.getResourceAsStream(SCHEMA_SCRIPT),
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
        description: 无 key 全链路 E2E Agent
        identity:
          agent_name: 小助手
          prompt: 你是 OryxOS 的全链路自测助手。
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        ---

        按脚本把用户说的话记进长期记忆。
        """;
  }
}
