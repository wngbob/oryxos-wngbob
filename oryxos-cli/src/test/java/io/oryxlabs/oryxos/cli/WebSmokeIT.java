package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Web Service 的装配冒烟：真起一个 SERVLET 容器，走真链路打四个查询端点。
 *
 * <p><b>它判的不是「逻辑对不对」，而是「接线炸没炸」</b>（FR-010）：控制器在不在扫描根里、{@code @EntityScan} /
 * {@code @EnableJpaRepositories} 指对没有、统一异常出口有没有被注册、{@code CliApplication} 的逐包列举漏没漏 {@code
 * io.oryxlabs.oryxos.web}——这几件事单元测试全都照不到，因为 {@code @WebMvcTest} 只起 MVC 切片、{@code MockMvc}
 * 连真容器都不存在。第 18 节实测过：这类问题只在「重命令真起上下文」时才以 {@code BeanDefinitionOverrideException} 或 {@code
 * NoUniqueBeanDefinitionException} 的形态炸出来。
 *
 * <p><b>凭证是假值</b>：本节只要 {@code ProviderProperties} 能绑上、{@code ChatModel} 能造出来，不真调模型——真调用归 {@code
 * ProviderSmokeIT}（那里带环境变量闸门）。用 {@code ${DEEPSEEK_API_KEY:...}} 的占位形态是为了和生产配置<b>同形</b>：
 * 真配了环境变量就用真的，没配就落默认假值，测试在任何机器上都跑得起来且不烧钱。
 *
 * <p><b>{@code @Tag("integration")}</b>：根 pom 的 surefire 默认排除该组，{@code mvn clean verify} 不跑它；手动跑法见
 * {@code ProviderSmokeIT} 的 javadoc（三个参数都必须写，类名以 {@code IT} 结尾不在默认 includes 内）。
 *
 * <p><b>关于「把 {@code user.dir} 指向临时工作区」这条原定做法（已实测推翻，2026-10-19）</b>： {@code
 * System.setProperty("user.dir", ...)} 在 JDK 21 上<b>对 {@code Paths.get("")} 不生效</b>——默认文件系统在首次访问时就把
 * {@code user.dir} 缓存下来了，进程内改属性不会传到 {@code UnixFileSystem.defaultDirectory()}（探针实测：改属性前后 {@code
 * Paths.get("").toAbsolutePath()} 是同一个值）。所以隔离改用<b>真正生效</b>的手段：注入一个指向临时目录的 {@link Workspace}
 * Bean（{@code @Primary} 顶掉 {@code CliAssemblyConfiguration.workspace()}）。
 * <b>它守的仍然是原定那条判据</b>——{@code /profiles} 读的是测试自己的空 Agent 目录，不读开发者机器上真实工作区里的 Agent， 结果与机器无关。
 */
@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect",
      "oryxos.providers[0].name=deepseek",
      "oryxos.providers[0].api-key=${DEEPSEEK_API_KEY:smoke-fake-key-not-used}"
    })
class WebSmokeIT {

  /** 建表脚本：表结构的唯一真相源（FR-011），与各 Repository 测试同一份。 */
  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  /**
   * 临时根目录。{@code @TempDir} 静态字段由 JUnit 注入并在整类结束后递归删除——本类<b>不</b>掏 {@code
   * Files.createTempDirectory}，否则每点名跑一次就在 {@code /tmp} 漏一个目录。
   */
  @TempDir static Path tempDir;

  @Autowired private TestRestTemplate rest;

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + tempDir.resolve("oryxos.db");
  }

  /** 库文件与容器端口都推迟到上下文刷新时才求值：那时静态 {@code @TempDir} 已经注入好了。 */
  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", WebSmokeIT::jdbcUrl);
  }

  /**
   * 用临时工作区顶掉生产的工作区 Bean。
   *
   * <p><b>{@code @Primary} 是必须的</b>：{@code CliAssemblyConfiguration.workspace()} 会照常产出另一个 {@code
   * Workspace} Bean，两个同类型候选同时在场，不标主就轮到 {@code profileLoader(Workspace)} 报歧义。
   */
  @TestConfiguration
  static class TempWorkspaceConfiguration {

    /**
     * @return 指向 {@code <tempDir>/.oryxos} 的工作区，与生产同一个构造器
     */
    @Bean
    @Primary
    Workspace smokeWorkspace() {
      return new Workspace(tempDir);
    }
  }

  /** 先建表再起上下文：{@code ddl-auto=none}，不起脚本 {@code /info} 的计数查询就会撞「表不存在」。 */
  @BeforeAll
  static void applySchemaScript() throws SQLException, IOException {
    Files.createDirectories(tempDir.resolve(".oryxos"));
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            WebSmokeIT.class.getResourceAsStream(SCHEMA_SCRIPT),
            "classpath 上找不到建表脚本 " + SCHEMA_SCRIPT)) {
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (Connection connection = DriverManager.getConnection(jdbcUrl());
        Statement statement = connection.createStatement()) {
      for (String sql : script.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  @Test
  @DisplayName("/health 真链路可达：库通、声明过的厂商配置就绪，整体 UP")
  void healthIsReachable() {
    ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/health", JsonNode.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = response.getBody();
    assertThat(body).isNotNull();
    // 信封是统一的四个字段（FR-005 / 裁决 ①），不是裸对象
    assertThat(body.path("code").asInt()).isEqualTo(200);
    assertThat(body.has("timestamp")).isTrue();
    assertThat(body.path("data").path("status").asText()).isEqualTo("UP");
    assertThat(body.path("data").path("db").asText()).isEqualTo("UP");
    // 声明了 deepseek 且凭证占位能解析 → 配置就绪；键集合恒等于 SUPPORTED_PROVIDERS
    assertThat(body.path("data").path("providers").has("deepseek")).isTrue();
    assertThat(body.path("data").path("providers").path("deepseek").asText()).isEqualTo("UP");
  }

  @Test
  @DisplayName("/info 真链路可达：四个计数各自问到了自己的数据源")
  void infoIsReachable() {
    ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/info", JsonNode.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode data = Objects.requireNonNull(response.getBody()).path("data");
    assertThat(data.path("name").asText()).isEqualTo("oryxos");
    assertThat(data.path("javaVersion").asText()).startsWith("21");
    // 临时工作区里没有 Agent，故为 0——它证明的是「读到了那个目录」，不是「有几条」
    assertThat(data.path("agents").asInt()).isZero();
    assertThat(data.path("activeSessions").asLong()).isZero();
    // 内置工具在装配期就全进注册表，与工作区里有没有 Agent 无关（FR-016：加工具成本恒定）
    assertThat(data.path("registeredTools").asInt()).isGreaterThanOrEqualTo(15);
    assertThat(data.path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("/profiles 真链路可达：读的是注入的临时工作区，不是开发者机器上的真实目录")
  void profilesIsReachable() {
    ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/profiles", JsonNode.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode data = Objects.requireNonNull(response.getBody()).path("data");
    assertThat(data.isArray()).isTrue();
    assertThat(data).isEmpty();
  }

  @Test
  @DisplayName("/tools 真链路可达：三种来源收敛成同一个视图，内置工具全在")
  void toolsIsReachable() {
    ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/tools", JsonNode.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode data = Objects.requireNonNull(response.getBody()).path("data");
    assertThat(data.isArray()).isTrue();
    assertThat(data.size()).isGreaterThanOrEqualTo(15);
    assertThat(data.findValuesAsText("name"))
        .contains("read_file", "write_file", "shell", "save_memory", "recall_memory");
    // 随包默认没有 MCP server，故全部来源都是 builtin；origin 与 source 相同（非 MCP 不加前缀）
    assertThat(data.findValuesAsText("source")).containsOnly("builtin");
  }
}
