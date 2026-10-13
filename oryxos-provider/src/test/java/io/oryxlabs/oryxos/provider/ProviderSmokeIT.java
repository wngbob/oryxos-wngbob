package io.oryxlabs.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.storage.entity.LlmCall;
import io.oryxlabs.oryxos.storage.repository.LlmCallRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 集成冒烟：用真 key 真调一次 DeepSeek，验证"key 对、依赖对、真的通"。
 *
 * <p><b>默认不跑</b>：打 {@link Tag} {@code integration}，根 pom 的 surefire 已把该组排除在默认构建之外——不能让外部 API
 * 的可用性变成自己流水线的可用性。另加一层环境变量闸门，保证在没配 key 的机器上即使被显式选中也只是"跳过"而非"失败"。
 *
 * <p>手动跑法（<b>三个参数都必须写</b>）：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx ./mvnw -pl oryxos-provider -am test \
 *     -DexcludedGroups=none -Dtest=ProviderSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>① {@code -am} 把 {@code oryxos-core} / {@code oryxos-storage} 一并拉进反应堆。漏了它，单模块构建会从 {@code ~/.m2}
 * 取旧快照 jar（只要跑过一次 {@code mvn install}，旧字节码就在那儿躺着）——本节实测踩到：旧 jar 里 {@code LlmCall()} 还是 {@code
 * protected}，报 {@code IllegalAccessError}，而源码与 {@code target/classes} 早已是 {@code public}。<b>全量
 * {@code mvn clean verify} 不会暴露这个坑</b>，那时兄弟模块走的是反应堆。② {@code -DexcludedGroups=none} 顶掉默认排除组——该默认值走
 * pom 的 {@code <excludedGroups>} 属性而非插件配置里的字面量，否则 Maven 的 {@code -D} 覆盖不了。③ 类名以 {@code IT} 结尾，不在
 * surefire 默认 includes 内，必须 {@code -Dtest} 点名；而 {@code -am} 之后这条过滤在 core/storage 里匹配不到任何测试，
 * surefire 默认把"点名了却一个没跑到"判为失败，故需再配 {@code failIfNoSpecifiedTests=false}。
 *
 * <p>本用例不 mock 任何东西：走真实的 {@link ProviderConfiguration} 显式映射表、真实的 DeepSeek、真实的 SQLite
 * （手工脚本建表）。这正是它相对单测的价值——单测证明逻辑对，它证明<b>接线</b>对。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(classes = ProviderSmokeIT.SmokeApplication.class)
@TestPropertySource(
    properties = {
      "oryxos.providers[0].name=deepseek",
      "oryxos.providers[0].api-key=${DEEPSEEK_API_KEY}"
    })
class ProviderSmokeIT {

  private static final String SCHEMA_SCRIPT = "/db/schema.sql";

  private static final String SESSION_ID = "smoke-session";

  private static final Path DB_FILE = createDatabaseFile();

  @Autowired private ProviderProperties properties;

  @Autowired private LlmCallRepository repository;

  /** 最小启动配置：实体与 Repository 在兄弟包，必须显式指名扫描范围。 */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(ProviderProperties.class)
  @EntityScan(basePackageClasses = LlmCall.class)
  @EnableJpaRepositories(basePackageClasses = LlmCallRepository.class)
  static class SmokeApplication {}

  private static Path createDatabaseFile() {
    try {
      return Files.createTempDirectory("oryxos-provider-smoke").resolve("oryxos.db");
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建冒烟测试用 SQLite 库文件", e);
    }
  }

  private static String jdbcUrl() {
    return "jdbc:sqlite:" + DB_FILE;
  }

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", ProviderSmokeIT::jdbcUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
  }

  @BeforeAll
  static void applySchemaScript() throws SQLException, IOException {
    String script;
    try (InputStream in =
        Objects.requireNonNull(
            ProviderSmokeIT.class.getResourceAsStream(SCHEMA_SCRIPT),
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

  private static Profile smokeProfile() {
    return new Profile(
        "smoke-agent",
        "冒烟用 Agent",
        null,
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("真调一次模型：拿到非空响应，且审计多了一条 success 为 true 的记录")
  void realCallReturnsTextAndLeavesAuditRecord() {
    Map<String, ChatModel> providers = new ProviderConfiguration().providerMap(properties);
    ProviderService service =
        new ProviderService(providers, new ToolSchemaAdapter(), new JpaLlmCallRecorder(repository));
    long before = repository.count();

    String answer =
        service
            .chat(SESSION_ID, smokeProfile(), new Prompt("用一句话说明你是谁，不要展开。"))
            .getResult()
            .getOutput()
            .getText();

    assertThat(answer).isNotBlank();
    assertThat(repository.count()).isEqualTo(before + 1);
    List<LlmCall> calls = repository.findBySessionIdOrderByCreatedAtDesc(SESSION_ID);
    assertThat(calls).isNotEmpty();
    assertThat(calls.get(0).getSuccess()).isTrue();
    assertThat(calls.get(0).getProvider()).isEqualTo("deepseek");
    assertThat(calls.get(0).getTotalTokens()).isNotNull();
  }
}
