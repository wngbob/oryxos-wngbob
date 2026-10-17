package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxlabs.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxlabs.oryxos.tool.search.DuckDuckGoSearchProvider;
import io.oryxlabs.oryxos.tool.search.SearchProvider;
import io.oryxlabs.oryxos.tool.search.TavilySearchProvider;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配的两条硬约束（contracts/assembly.md §2.3 与 §3）。
 *
 * <p><b>为什么必须起真上下文</b>：{@code @ConditionalOnMissingBean} 的求值顺序、让位机制、Bean 声明顺序——这三条都只在 真实启动流程里才求值。手工
 * {@code new} 一个 {@code ToolRegistry} 来断言，等于把要守的东西绕过去，测试变成自证。
 *
 * <p><b>两条断言都是「恰好一个」而不是「存在一个是」</b>：后者在「两个都在」时照样绿，而「两个都在」正是本节最危险的坏法 ——它只在重命令起上下文时才炸成 {@code
 * NoUniqueBeanDefinitionException}，{@code --help} 扫不出来。
 *
 * <p><b>不依赖任何 {@code application.yaml}</b>：数据源参数在启动时以 properties 直接给。本模块的 pom 明令 MUST NOT 放第二份
 * {@code application.yaml}（唯一那份在 {@code oryxos-boot}，重命令要读的 {@code spring.datasource.*} 必须与 CLI 的类同
 * classpath），本测试不碰那条约束。{@code oryxos.providers} 也整个不给——空清单能过 {@code ProviderProperties.validate()}。
 *
 * <p><b>{@code oryxos-boot} 不在本模块的依赖里</b>，故随包的三块白名单配置在本测试的上下文里<b>整个缺失</b>——这正是 {@code
 * SandboxProperties} 的 null 归一化要在真装配路径上被验到的原因（缺了它启动就 NPE）。
 */
class ToolAssemblyTest {

  /** 启动路径与生产同源，见 {@link CliTestContext}。 */
  private static ConfigurableApplicationContext boot(Path dbFile, Class<?>... extraSources) {
    return CliTestContext.boot(dbFile, extraSources);
  }

  @Test
  @DisplayName("装配后容器里 ToolCatalog 类型的 Bean 恰好一个，且是 ToolRegistry")
  void toolCatalog_hasExactlyOneBean_andItIsTheRegistry(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = boot(tmp.resolve("oryxos.db"))) {
      Map<String, ToolCatalog> beans = context.getBeansOfType(ToolCatalog.class);

      // 「恰好一个」同时抓住两种坏法：空实现没让位（两个）、以及注册表压根没装配上（零个）
      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next()).isInstanceOf(ToolRegistry.class);
    }
  }

  @Test
  @DisplayName("容器里 Sandbox 类型的 Bean 恰好一个且是 WhitelistSandbox")
  void sandbox_hasExactlyOneBean_andItIsTheWhitelistSandbox(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = boot(tmp.resolve("oryxos.db"))) {
      Map<String, Sandbox> beans = context.getBeansOfType(Sandbox.class);

      // 「恰好一个」而不是「存在一个是」：后者在「两个都在」时假绿，而那种坏法只在重命令起上下文时才炸
      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next()).isInstanceOf(WhitelistSandbox.class);
    }
  }

  @Test
  @DisplayName("容器里已有别的 Sandbox Bean 时，白名单实现让位（覆盖接缝可复现）")
  void whenDeploymentSandboxIsPresent_theWhitelistSandboxBacksOff(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context =
        boot(tmp.resolve("oryxos.db"), DeploymentSandboxOverride.class)) {
      Map<String, Sandbox> beans = context.getBeansOfType(Sandbox.class);

      // 断言「恰好一个且是替身」——若让位机制失效，这里会是两个，且白名单实现也在
      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next())
          .isInstanceOf(DeploymentSandboxOverride.SandboxImpl.class);
    }
  }

  /**
   * 三个 snake_case 配置键字面量的守卫：改键名即变红。
   *
   * <p><b>键名写错的后果是「白名单静默为空 = 全拒」</b>：装上跑不了、日志无异常——没有这条用例，这个错要等到人工验收才被发现。本模块没有 {@code
   * application.yaml}，故这里喂进去的就是唯一的配置源，绑不上一定表现为「全部被拒」。
   *
   * <p>用带索引的键（{@code file.allowed_paths[0]}）而不是整串：{@code List} 类型绑定的就是索引键，这也正是运维在 properties /
   * 环境变量形态下会写出来的东西。
   */
  @Test
  @DisplayName("file.allowed_paths / shell.allowed_commands / http.allowed_domains 三个键真的绑得上")
  void sandboxProperties_areBoundFromSnakeCaseKeys(@TempDir Path tmp) {
    Path allowedDir = tmp.resolve("workspace");
    Map<String, Object> properties =
        Map.of(
            "file.allowed_paths[0]", allowedDir.toString(),
            "shell.allowed_commands[0]", "ls",
            "http.allowed_domains[0]", "api.example.com");

    try (ConfigurableApplicationContext context =
        CliTestContext.boot(tmp.resolve("oryxos.db"), properties)) {
      Sandbox sandbox = context.getBeansOfType(Sandbox.class).values().iterator().next();

      // 白名单内的放行
      assertThatCode(
              () ->
                  sandbox.enforce(
                      new SandboxAction(
                          ActionType.FILE_READ, allowedDir.resolve("a.txt").toString())))
          .doesNotThrowAnyException();
      assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls -la")))
          .doesNotThrowAnyException();
      assertThatCode(
              () ->
                  sandbox.enforce(
                      new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com/x")))
          .doesNotThrowAnyException();

      // 白名单外的拒绝——若键名没绑上，白名单为空，这四条照样拒；所以真正判成败的是上面那三条「放行」
      assertThatThrownBy(
              () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")))
          .isInstanceOf(SandboxViolationException.class);
      assertThatThrownBy(
              () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /")))
          .isInstanceOf(SandboxViolationException.class);
      assertThatThrownBy(
              () ->
                  sandbox.enforce(
                      new SandboxAction(
                          ActionType.HTTP_REQUEST, "https://evil.example.com.evil.io/x")))
          .isInstanceOf(SandboxViolationException.class);
    }
  }

  @Test
  @DisplayName("没配搜索 key 时回退到免 key 的 DuckDuckGo（装上就能跑这条原则不破）")
  void searchProvider_withoutKey_fallsBackToDuckDuckGo() {
    SearchProvider provider = new CliAssemblyConfiguration().searchProvider("");

    assertThat(provider).isInstanceOf(DuckDuckGoSearchProvider.class);
    assertThat(provider.endpoint()).isEqualTo("https://api.duckduckgo.com/");
  }

  @Test
  @DisplayName("配了搜索 key 就用带 key 的 Tavily（长尾查询能真搜到结果）")
  void searchProvider_withKey_usesTavily() {
    // 假 key：这里只验分流走的是哪一支，不会真的发请求（构造器不发请求）
    SearchProvider provider = new CliAssemblyConfiguration().searchProvider("tvly-test-key");

    assertThat(provider).isInstanceOf(TavilySearchProvider.class);
    assertThat(provider.endpoint()).isEqualTo("https://api.tavily.com/search");
  }

  /**
   * 为什么这两条走直接方法调用而不是起上下文：这两条要验的是「属性为空 / 非空时分流到哪一支」。{@code searchProvider(...)} 的分流逻辑本身不依赖
   * 容器，直接调用它反而更准——起上下文再断言 Bean 类型，验到的是「同一件事绕了一圈」。
   *
   * <p>（沙箱那三条配置键绑定用例则<b>必须</b>起上下文：绑定期是容器的事，直接调方法绕过了 {@code @ConfigurationProperties} 这一步， 等于没验。）
   */

  /**
   * 部署方自建 {@code Sandbox} Bean 的替身：验的是「容器里已有别的同类型 Bean 时，白名单实现让位」这件事本身。
   *
   * <p>用具体类而不是匿名类或 {@code @Primary}：匿名类无法 {@code isInstanceOf} 断言，{@code @Primary} 是「永远优先用假的」
   * ——正好反过来，白名单实现会被永久遮蔽（contracts/assembly.md §6.2 / §6.4）。
   */
  @Configuration
  static class DeploymentSandboxOverride {

    @Bean
    Sandbox deploymentSandbox() {
      return new SandboxImpl();
    }

    /** 部署方实现的占位身份：本测试只关心「它存在且接管了」这件事。 */
    static final class SandboxImpl implements Sandbox {

      @Override
      public void enforce(SandboxAction action) {
        // 替身不做校验：本测试只验让位机制
      }
    }
  }
}
