package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxlabs.oryxos.channel.cli.CliChannel;
import io.oryxlabs.oryxos.core.engine.ToolExecutor;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.tool.ToolResult;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * 人推链路的真·集成验收（第 27 节「验收 harness」核心）。
 *
 * <p><b>它把「人工对账」固化成可一键重演的测试</b>：三根支柱——<b>对话答得出</b>（真模型、真工具、真审计）、
 * <b>记忆查得到</b>（写得进、跨会话读得出）、<b>工具查得到</b>——加兜底的两条：<b>失败也记账</b>、<b>三面同源</b>。
 *
 * <p><b>默认不跑</b>：打 {@link Tag} {@code integration} + 环境变量闸门。手动跑法：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx ./mvnw -pl oryxos-cli -am test \
 *     -DexcludedGroups=none -Dtest=HumanTriggerFlowIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>它会真花钱</b>：几个 LLM 调用而已。跑到它的机器必须能出网（模型在 DeepSeek、天气在 wttr.in）。 无 key 的全链路判卷由 {@code
 * MockProviderFlowTest} / {@code MockAgentE2ETest} 在 gate 内完成——本类补的是「真模型也走同一条路」。
 *
 * <p><b>每个测试用各自的 {@code userId}</b>：会话标识 = {@code channel:user:profile}，共用默认用户会让几个测试落到同一个会话上，
 * 而「历史恰好四条」「恰好两次模型调用」这类断言是按会话数账的——测试之间互相污染，红起来会指向无辜的那条。
 *
 * <p><b>隔离手段与 {@code MockAgentE2ETest} 相同</b>：{@code oryxos.root} 把工作区指到临时目录、SQLite 指到同一个目录，
 * 静态初始化块里备好 Agent 与建表脚本（上下文在 {@code @BeforeAll} 之前就刷新，晚于它的准备都来不及）。
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
class HumanTriggerFlowIT {

  /** 用于三根支柱的 Agent：真模型、真工具（http_get 查天气、save_memory 记事实）。 */
  private static final String AGENT = "weather-agent";

  /** 用于「Provider 挂掉」的 Agent：模型名不存在，DeepSeek 一定回 4xx。 */
  private static final String BROKEN_AGENT = "broken-agent";

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-it-" + UUID.randomUUID());

  static {
    System.setProperty("oryxos.root", ROOT.toString());
    try {
      writeAgent(AGENT, weatherAgentMd());
      writeAgent(BROKEN_AGENT, brokenAgentMd());
      applySchema();
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("准备集成测试临时工作区失败：" + ROOT, e);
    }
  }

  @Autowired private TestRestTemplate rest;

  @Autowired private ToolExecutor toolExecutor;

  @Autowired private CliChannel cliChannel;

  @Autowired private ProfileRegistry profileRegistry;

  @DynamicPropertySource
  static void tempWorkspaceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
    // 文件工具的白名单指到临时工作区：失败路径要在「白名单内、文件不存在」这条路上判，而不是靠越界
    registry.add("file.allowed_paths", () -> List.of(ROOT.toString()));
    // 天气查询的真实端点（随包默认清单里没有它）。整表替换而不是追加一项：对 YAML 里已有的列表
    // 只补一个更大的下标，多出来的那一项绑不上（Binder 报 "elements ... were left unbound"、上下文起不来）
    registry.add(
        "http.allowed_domains",
        () ->
            List.of(
                "api.duckduckgo.com",
                "api.tavily.com",
                "open.feishu.cn",
                "open.larksuite.com",
                "qyapi.weixin.qq.com",
                "wttr.in"));
  }

  @AfterAll
  static void clearRootOverride() {
    System.clearProperty("oryxos.root");
    deleteRecursively(ROOT);
  }

  // ---------------------------------------------------------------- 支柱一

  @Test
  @DisplayName("支柱一 · 对话答得出：一次「天气穿衣」走完两轮 ReAct + 一次工具，账目不多不少")
  void dialogAnswersWithExactlyOneToolCall() throws SQLException {
    String sessionId = createSession(AGENT, "p1");

    JsonNode reply =
        post("/api/v1/sessions/" + sessionId + "/messages", Map.of("content", "今天北京天气怎么样，穿什么合适？"));
    assertThat(reply.path("data").path("reply").asText()).as("答复不能为空").isNotBlank();

    // 历史：用户问的、模型两次回复、一次工具结果——四条按序齐备
    JsonNode history = getData("/api/v1/sessions/" + sessionId).path("messages");
    assertThat(history.get(0).path("role").asText()).isEqualTo("user");
    assertThat(history.findValuesAsText("role"))
        .containsExactly("user", "assistant", "tool", "assistant");

    // 对账：llm_calls 恰 2 条、tool_invocations 恰 1 条（http_get）、全部成功——多一条少一条都算失败
    assertThat(count("select count(*) from llm_calls where session_id = ?", sessionId))
        .isEqualTo(2);
    assertThat(
            count("select count(*) from llm_calls where session_id = ? and success = 1", sessionId))
        .isEqualTo(2);
    assertThat(count("select count(*) from tool_invocations where session_id = ?", sessionId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ? and success = 1",
                sessionId))
        .isEqualTo(1);
    assertThat(text("select tool_name from tool_invocations where session_id = ?", sessionId))
        .isEqualTo("http_get");
  }

  // ---------------------------------------------------------------- 支柱二

  @Test
  @DisplayName("支柱二 · 记忆查得到：写得进 MEMORY.md，新会话里也用得上")
  void memoryIsWrittenAndReadableAcrossSessions() throws SQLException, IOException {
    String writerSession = createSession(AGENT, "p2-writer");
    post("/api/v1/sessions/" + writerSession + "/messages", Map.of("content", "记住：我在北京，怕冷"));

    // 写进了文件（默认 markdown 档），也读得到
    assertThat(
            Files.readString(ROOT.resolve("memory").resolve("MEMORY.md"), StandardCharsets.UTF_8))
        .contains("北京");
    JsonNode memory = getData("/api/v1/memory");
    assertThat(memory.path("core").asText() + memory.path("archival").asText()).contains("北京");

    // 跨会话：换一个会话问，答复应当用上这条记忆
    String questionSession = createSession(AGENT, "p2-reader");
    assertThat(questionSession).isNotEqualTo(writerSession);
    JsonNode reply =
        post(
            "/api/v1/sessions/" + questionSession + "/messages",
            Map.of("content", "我在哪个城市？我只说过一次。"));
    assertThat(reply.path("data").path("reply").asText()).contains("北京");
  }

  // ---------------------------------------------------------------- 支柱三

  @Test
  @DisplayName("支柱三 · 工具查得到：对外清单与 Profile 声明的工具集一致，关键工具都在")
  void registeredToolsMatchProfileDeclaration() {
    List<String> declared =
        profileRegistry
            .find(AGENT)
            .map(Profile::tools)
            .orElseThrow(() -> new AssertionError("Agent '" + AGENT + "' 没加载出来"));

    List<String> exposed = getData("/api/v1/tools").findValuesAsText("name");

    assertThat(exposed).isNotEmpty();
    assertThat(exposed).contains("http_get", "save_memory", "recall_memory");
    assertThat(exposed).containsAll(declared);
  }

  // ---------------------------------------------------------------- 兜底

  @Test
  @DisplayName("兜底 · 失败也记账：Sandbox 拦截 / 工具失败 / Provider 故障都落 success=false，系统不崩")
  void failurePathsAreAudited() throws SQLException {
    String sessionId = "failure-path-session";

    // ① Sandbox 拦截：/etc/passwd 不在工作区内，IO 在发生之前就被拦下
    ToolResult blocked =
        toolExecutor.execute(
            sessionId,
            new AssistantMessage.ToolCall(
                "fail-sandbox", "function", "read_file", "{\"path\":\"/etc/passwd\"}"));
    assertThat(blocked.success()).isFalse();
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ? and success = 0",
                sessionId))
        .isEqualTo(1);

    // ② 工具自身失败：白名单内、文件不存在——不是「越界」，而是工具如实报错
    ToolResult missing =
        toolExecutor.execute(
            sessionId,
            new AssistantMessage.ToolCall(
                "fail-missing", "function", "read_file", "{\"path\":\"" + ROOT + "/nope.txt\"}"));
    assertThat(missing.success()).isFalse();
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ? and success = 0",
                sessionId))
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from tool_invocations where session_id = ?"
                    + " and success = 0 and error_message is not null",
                sessionId))
        .isEqualTo(2);

    // ③ Provider 故障：模型名不存在 → 真 4xx → 先落 success=false 再上抛成 503
    long failuresBefore = count("select count(*) from llm_calls where success = 0");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> response =
        rest.postForEntity(
            "/api/v1/agents/" + BROKEN_AGENT + "/invoke",
            new HttpEntity<>(Map.of("content", "你好"), headers),
            JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(count("select count(*) from llm_calls where success = 0"))
        .isGreaterThan(failuresBefore);
    // 失败没有拖垮进程：紧接着的请求照常
    assertThat(rest.getForEntity("/api/v1/health", JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  // ---------------------------------------------------------------- 三面同源

  @Test
  @DisplayName("三面同源：CLI 入口写的会话，REST 列表与详情读回来是同一份数据")
  void cliAndRestSeeTheSameSession() {
    cliChannel.send(AGENT, "记住：我在北京，怕冷");

    String cliSessionId = "cli:local:" + AGENT;
    JsonNode detail = getData("/api/v1/sessions/" + cliSessionId);
    assertThat(detail.path("sessionId").asText()).isEqualTo(cliSessionId);
    assertThat(detail.path("channel").asText()).isEqualTo("cli");
    assertThat(detail.path("messages").size()).isGreaterThanOrEqualTo(2);
    assertThat(detail.path("messages").get(0).path("content").asText()).contains("北京");

    JsonNode summary = null;
    for (JsonNode node : getData("/api/v1/sessions")) {
      if (cliSessionId.equals(node.path("sessionId").asText())) {
        summary = node;
      }
    }
    assertThat(summary).as("CLI 会话必须出现在管理台「会话」页的数据源里").isNotNull();
    assertThat(summary.path("messageCount").asInt()).isEqualTo(detail.path("messages").size());
  }

  // ---------------------------------------------------------------- 辅助

  private String createSession(String profile, String userId) {
    JsonNode created = post("/api/v1/sessions", Map.of("profile", profile, "userId", userId));
    return created.path("data").path("sessionId").asText();
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

  private JsonNode getData(String path) {
    ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return Objects.requireNonNull(response.getBody()).path("data");
  }

  private static int count(String sql, String sessionId) throws SQLException {
    try (Connection connection = connection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : -1;
      }
    }
  }

  private static String text(String sql, String sessionId) throws SQLException {
    try (Connection connection = connection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  /** 不带参数版本的计数（Provider 故障那条要跨会话数）。 */
  private static long count(String sql) throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      return resultSet.next() ? resultSet.getLong(1) : -1;
    }
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
  }

  private static void applySchema() throws IOException, SQLException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            HumanTriggerFlowIT.class.getResourceAsStream(SCHEMA_SCRIPT),
            "classpath 上找不到建表脚本 " + SCHEMA_SCRIPT)) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      for (String sql : script.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  private static void writeAgent(String name, String content) throws IOException {
    Path dir = Files.createDirectories(ROOT.resolve("agents").resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
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

  private static String weatherAgentMd() {
    return """
        ---
        description: 人推全流程对账 Agent
        identity:
          agent_name: 天气助手
          prompt: 你是天气与生活建议助手。凡是被问到天气，你都必须先调用 http_get 查询真实天气再回答：URL 固定用 https://wttr.in/<城市英文名>?format=3，不要凭记忆回答。凡是被要求「记住」某事，你必须调用 save_memory 原样保存，然后确认。回答用中文，简洁。
        provider:
          name: deepseek
          model: deepseek-chat
        tools:
          - http_get
          - save_memory
          - recall_memory
        ---

        对账专用 Agent：一次对话要留下两轮模型调用与一次工具调用的痕迹。
        """;
  }

  private static String brokenAgentMd() {
    return """
        ---
        description: 故障注入 Agent（模型名不存在）
        identity:
          agent_name: 故障注入
          prompt: 你是一个用于验证失败审计的 Agent。
        provider:
          name: deepseek
          model: no-such-model-for-failure-audit
        tools:
          - save_memory
        ---

        这个 Agent 的模型名是故意写错的：每次调用都会在 Provider 侧失败。
        """;
  }
}
