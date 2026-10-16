package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import io.oryxlabs.oryxos.core.memory.MemorySource;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.MemoryServiceImpl;
import io.oryxlabs.oryxos.memory.store.InMemoryMemoryStore;
import io.oryxlabs.oryxos.memory.store.MarkdownMemoryStore;
import io.oryxlabs.oryxos.memory.store.Mem0MemoryStore;
import io.oryxlabs.oryxos.memory.store.SqliteMemoryStore;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 记忆装配的六条硬约束（contracts/memory-config.md §3）。
 *
 * <p><b>为什么必须起真上下文</b>：让位机制、{@code @Value} 占位解析、Bean 声明顺序——三条都只在真实启动流程里才求值。手工 {@code new
 * MemoryServiceImpl(store)} 来断言，等于把要守的东西全绕过去，测试变成自证。
 *
 * <p><b>为什么在 {@code oryxos-cli}</b>：装配后的真实上下文只有本模块看得见（{@link OryxToolContractTest} 已定这条口径）。
 *
 * <p><b>本模块的测试 classpath 上没有 {@code application.yaml}</b>（唯一那份在 {@code oryxos-boot}），所以每条用例都<b>显式把
 * {@code oryxos.memory.*} 喂进去</b>——这反而更好：验的是「配置写什么就装配成什么」，而不是「本机恰好配了什么」。
 */
class MemoryAssemblyTest {

  /** 被守护的装配源文件（仓库根相对路径）。 */
  private static final String ASSEMBLY =
      "oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/CliAssemblyConfiguration.java";

  /** 合法取值清单，报错文案里必须逐字出现。 */
  private static final String LEGAL_BACKENDS = "markdown / sqlite / mem0";

  @Test
  @DisplayName("让位机制生效：容器里 MemorySource 恰好一个，且是 MemoryServiceImpl")
  void memorySource_hasExactlyOneBean_andItIsTheFacade(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = boot(tmp, "markdown")) {
      Map<String, MemorySource> beans = context.getBeansOfType(MemorySource.class);

      // 「恰好一个」同时抓住两种坏法：空实现没让位（两个）、以及门面压根没装配上（零个）
      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next()).isInstanceOf(MemoryServiceImpl.class);
    }
  }

  @Test
  @DisplayName("默认档（配置里不写 backend）就是 markdown——不配任何东西也能用")
  void defaultBackend_isMarkdown(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      assertThat(storeOf(context.getBean(MemoryService.class)))
          .isInstanceOf(MarkdownMemoryStore.class);
    }
  }

  @Test
  @DisplayName("三个取值各造出对应那一档后端——换后端只改配置，门面以上一行不动")
  void eachBackendValue_buildsItsOwnStore(@TempDir Path tmp) {
    assertThat(storeOf(bootAndGetService(tmp, "markdown"))).isInstanceOf(MarkdownMemoryStore.class);
    assertThat(storeOf(bootAndGetService(tmp, "sqlite"))).isInstanceOf(SqliteMemoryStore.class);
    // 地址必须给：mem0 档没有地址就起不来（下一条用例专门验这个）
    try (ConfigurableApplicationContext context =
        boot(tmp, "mem0", Map.of("oryxos.memory.mem0.base-url", "http://mem0.local:8080"))) {
      assertThat(storeOf(context.getBean(MemoryService.class))).isInstanceOf(Mem0MemoryStore.class);
    }
  }

  @Test
  @DisplayName("未知取值报错并列出合法取值，MUST NOT 静默退回默认档")
  void unknownBackend_failsLoudly_listingLegalValues(@TempDir Path tmp) {
    // 静默退回默认档最坏：运维改了配置、重启、以为生效了，实际还在用旧的——比启动失败难查得多
    assertThatThrownBy(() -> boot(tmp, "postgres"))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("postgres")
        .hasMessageContaining(LEGAL_BACKENDS);
  }

  @Test
  @DisplayName("backend=mem0 但地址变量缺失时报错：消息含变量名 MEM0_BASE_URL，不含任何地址值")
  void mem0WithoutBaseUrl_failsNamingOnlyTheVariable(@TempDir Path tmp) {
    assertThatThrownBy(
            () ->
                boot(
                    tmp,
                    "mem0",
                    // 配置侧两个子键都带默认值，缺失永远不会在配置加载时失败——这条校验是唯一的拦截点
                    Map.of(
                        "oryxos.memory.mem0.base-url", "", "oryxos.memory.mem0.user-id", "tester")))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MEM0_BASE_URL")
        // 只带变量名：把未解析的占位符或任何地址当值带出去，都是 FR-018 / FR-019 禁止的
        .hasMessageNotContaining("${")
        .hasMessageNotContaining("http");
  }

  @Test
  @DisplayName("InMemoryMemoryStore 选不中：它不在 backend 取值里，也不是任何 Bean 的类型")
  void inMemoryStore_isNotSelectable(@TempDir Path tmp) {
    assertThatThrownBy(() -> boot(tmp, "inmemory"))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LEGAL_BACKENDS)
        // 报错会回显调用方给的那个值（它不是机密，回显才让人看清自己写错了什么），所以这里不能笼统地断言
        // 「消息不含 InMemory」——要钉的是合法取值清单里没有类名，即不接受拿实现类名当取值
        .hasMessageNotContaining(InMemoryMemoryStore.class.getSimpleName());

    try (ConfigurableApplicationContext context = boot(tmp, "markdown")) {
      assertThat(context.getBeansOfType(InMemoryMemoryStore.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("memoryService() 声明在 memorySource() 之前——顺序反了容器里会出现两个 MemorySource")
  void memoryService_isDeclaredBeforeTheEmptySource() {
    // 行为侧的守点由「恰好一个 MemorySource」承担；这条守的是源码顺序本身——顺序一旦反过来，那条断言会以
    // NoUniqueBeanDefinitionException 的形式炸，而错误信息指向的是 PromptBuilder 的注入点，不指向这里
    String source = read(ASSEMBLY);
    int serviceAt = source.indexOf("public MemoryService memoryService(");
    int sourceAt = source.indexOf("public MemorySource memorySource(");

    if (serviceAt < 0 || sourceAt < 0) {
      fail(
          "在 %s 里找不到 memoryService() 或 memorySource() 的声明（改签名了？）——本类靠这两处定位判断声明顺序，"
              + "找不到时下面两条断言会失去意义，故直接失败而不是静默跳过。",
          ASSEMBLY);
    }
    assertThat(serviceAt)
        .as("@ConditionalOnMissingBean(MemorySource.class) 按 @Bean 声明顺序求值，空实现必须先看到门面已在容器里")
        .isLessThan(sourceAt);
  }

  @Test
  @DisplayName("记忆工具确实进了注册表——装配处那一行 registerAnnotated 落地了")
  void memoryTools_areRegistered(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = boot(tmp, "markdown")) {
      List<String> names =
          context.getBean(ToolRegistry.class).registered().stream()
              .map(entry -> entry.tool().getName())
              .toList();

      assertThat(names).contains("save_memory");
    }
  }

  private static ConfigurableApplicationContext boot(Path tmp, String backend) {
    return boot(tmp, backend, Map.of());
  }

  private static ConfigurableApplicationContext boot(
      Path tmp, String backend, Map<String, Object> extraProperties) {
    Map<String, Object> properties = new java.util.LinkedHashMap<>(extraProperties);
    properties.put("oryxos.memory.backend", backend);
    return CliTestContext.boot(tmp.resolve("oryxos.db"), properties);
  }

  private static MemoryService bootAndGetService(Path tmp, String backend) {
    // 调用方负责 close：这里只需要读一次门面持有的后端类型，读完立刻关，不留常驻上下文
    try (ConfigurableApplicationContext context = boot(tmp, backend)) {
      return context.getBean(MemoryService.class);
    }
  }

  /**
   * 取门面持有的后端。
   *
   * <p>为什么走反射而不是给门面加一个 {@code store()} 访问器：那会把「后端是谁」变成对外概念，而它<b>刻意</b>是对外不可见的
   * ——上层不该知道底下是哪一档。反射只出现在测试里，正好是这件事该待的地方。
   *
   * @param service 门面
   * @return 它持有的 {@code LongTermMemoryStore}
   */
  private static Object storeOf(MemoryService service) {
    return ReflectionTestUtils.getField(service, "store");
  }

  private static String read(String relativePath) {
    Path file = repoRoot().resolve(relativePath);
    if (!Files.isRegularFile(file)) {
      fail("找不到装配源文件（仓库布局变了？）：" + file);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException("读不了装配源文件: " + file, e);
    }
  }

  /** 从 surefire 的 cwd（模块目录）往上找仓库根；找不到直接失败，不静默跳过。 */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-cli")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
