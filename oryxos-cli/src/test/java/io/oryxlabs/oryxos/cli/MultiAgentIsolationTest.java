package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
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
import java.util.List;
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
 * 多 Agent 并存的两条隔离边界（第 28 节「多 Agent 验」，mock 驱动、gate 内无 key）。
 *
 * <p>两个差异明显的 Profile 跑在同一实例上：{@code agent-file} 只声明文件工具，{@code agent-http} 只声明 HTTP 工具。守点两条：
 *
 * <ul>
 *   <li><b>工具隔离</b>：{@link ToolCatalog#availableFor(Profile)} 给每个 Profile 的清单恰是它自己声明的子集——
 *       模型可见层拿不到对方独有的工具；
 *   <li><b>会话隔离</b>：两边的会话 id 带各自的 Profile 名，历史互不串——防的是「哪处代码用了默认 Profile 把 两边搅一起」。
 * </ul>
 *
 * <p><b>定时隔离</b>（A 的定时抛异常、B 的下个触发点照常）需要确定性的失败注入，单独立卷在 {@code MultiAgentScheduleIsolationTest}——那里把
 * {@code AgentService} 换成 mock，才能让「A 必炸、B 必成」 只由测试决定，而不是碰运气。
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
class MultiAgentIsolationTest {

  private static final String FILE_AGENT = "agent-file";
  private static final String HTTP_AGENT = "agent-http";

  private static final String FILE_FACT = "文件 Agent 的会话内容：整理一下 docs 目录";
  private static final String HTTP_FACT = "HTTP Agent 的会话内容：查一下接口状态";

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-multi-agent-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      writeAgent(FILE_AGENT, "只给文件工具", List.of("read_file", "list_dir"));
      writeAgent(HTTP_AGENT, "只给 HTTP 工具", List.of("http_get"));
      applySchema();
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备多 Agent 临时工作区失败：" + ROOT, e);
    }
  }

  @Autowired private TestRestTemplate rest;

  @Autowired private ProfileRegistry profileRegistry;

  @Autowired private ToolCatalog toolCatalog;

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
  @DisplayName("工具隔离：每个 Profile 的可用工具恰是它声明的子集，拿不到对方独有的工具")
  void toolsAreFilteredPerProfile() {
    Profile fileAgent = profile(FILE_AGENT);
    Profile httpAgent = profile(HTTP_AGENT);

    assertThat(names(toolCatalog.availableFor(fileAgent)))
        .containsExactlyInAnyOrder("read_file", "list_dir")
        .doesNotContain("http_get");
    assertThat(names(toolCatalog.availableFor(httpAgent)))
        .containsExactly("http_get")
        .doesNotContain("read_file", "list_dir");

    // 全局注册表两份都认得——隔离发生在「按 Profile 过滤」这一层，不是注册表缺了谁
    assertThat(toolCatalog.find("read_file")).isPresent();
    assertThat(toolCatalog.find("http_get")).isPresent();
  }

  @Test
  @DisplayName("会话隔离：两个 Agent 各自建会话、各聊各的，历史互不串")
  void sessionsDoNotLeakBetweenProfiles() {
    String fileSession = createSession(FILE_AGENT);
    String httpSession = createSession(HTTP_AGENT);
    assertThat(fileSession).isEqualTo("web:default:" + FILE_AGENT);
    assertThat(httpSession).isEqualTo("web:default:" + HTTP_AGENT);

    send(fileSession, FILE_FACT);
    send(httpSession, HTTP_FACT);

    JsonNode fileHistory = history(fileSession);
    JsonNode httpHistory = history(httpSession);
    assertThat(texts(fileHistory)).contains(FILE_FACT).doesNotContain(HTTP_FACT);
    assertThat(texts(httpHistory)).contains(HTTP_FACT).doesNotContain(FILE_FACT);

    // 列表里两条各自带自己的 Profile 名，没有被「默认 Profile」搅在一起
    JsonNode summaries = getData("/api/v1/sessions");
    JsonNode fileSummary = summaryOf(summaries, fileSession);
    JsonNode httpSummary = summaryOf(summaries, httpSession);
    assertThat(fileSummary.path("profile").asText()).isEqualTo(FILE_AGENT);
    assertThat(httpSummary.path("profile").asText()).isEqualTo(HTTP_AGENT);
  }

  private Profile profile(String name) {
    return profileRegistry.find(name).orElseThrow();
  }

  private static List<String> names(List<OryxTool> tools) {
    return tools.stream().map(OryxTool::getName).toList();
  }

  private String createSession(String profile) {
    JsonNode created = postData("/api/v1/sessions", Map.of("profile", profile));
    return created.path("sessionId").asText();
  }

  private void send(String sessionId, String content) {
    JsonNode sent =
        postData("/api/v1/sessions/" + sessionId + "/messages", Map.of("content", content));
    assertThat(sent.path("reply").asText()).isNotBlank();
  }

  private JsonNode history(String sessionId) {
    return getData("/api/v1/sessions/" + sessionId).path("messages");
  }

  private static List<String> texts(JsonNode messages) {
    return messages.findValuesAsText("content");
  }

  private static JsonNode summaryOf(JsonNode summaries, String sessionId) {
    for (JsonNode node : summaries) {
      if (sessionId.equals(node.path("sessionId").asText())) {
        return node;
      }
    }
    throw new AssertionError("会话列表里找不到 " + sessionId);
  }

  private JsonNode getData(String path) {
    ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return Objects.requireNonNull(response.getBody()).path("data");
  }

  private JsonNode postData(String path, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode responseBody = Objects.requireNonNull(response.getBody());
    assertThat(responseBody.path("code").asInt()).isEqualTo(200);
    return responseBody.path("data");
  }

  private static void writeAgent(String name, String description, List<String> tools)
      throws IOException {
    Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(name));
    String toolLines = String.join("\n", tools.stream().map(tool -> "  - " + tool).toList());
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        """
        ---
        description: %s
        identity:
          agent_name: %s
          prompt: 你是 %s，按自己的工具清单干活。
        provider:
          name: mock
          model: mock-model
        tools:
        %s
        ---

        只说一句话。
        """
            .formatted(description, name, name, toolLines),
        StandardCharsets.UTF_8);
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            MultiAgentIsolationTest.class.getResourceAsStream(SCHEMA_SCRIPT),
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
