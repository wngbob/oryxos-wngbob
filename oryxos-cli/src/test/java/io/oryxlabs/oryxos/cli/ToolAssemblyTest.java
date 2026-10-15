package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
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
  @DisplayName("临时沙箱确实挂上了：容器里 Sandbox 类型的 Bean 恰好一个且是 PermissiveSandbox")
  void sandbox_hasExactlyOneBean_andItIsThePermissiveStandIn(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = boot(tmp.resolve("oryxos.db"))) {
      Map<String, Sandbox> beans = context.getBeansOfType(Sandbox.class);

      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next()).isInstanceOf(PermissiveSandbox.class);
    }
  }

  @Test
  @DisplayName("第 24 节的真实现一挂上，临时沙箱就自动让位（让位机制可复现，SC-009）")
  void whenRealSandboxIsPresent_theStandInBacksOff(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context =
        boot(tmp.resolve("oryxos.db"), RealSandboxStandIn.class)) {
      Map<String, Sandbox> beans = context.getBeansOfType(Sandbox.class);

      // 断言「恰好一个且是替身」——若让位机制失效，这里会是两个，且本节那个 PermissiveSandbox 也在
      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next())
          .isInstanceOf(RealSandboxStandIn.SandboxImpl.class);
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
   * 为什么这两条走直接方法调用而不是起上下文：{@code CliTestContext.boot} 只接受「额外的配置类」，<b>不能注入属性</b>， 而这两条要验的正是「属性为空 /
   * 非空时分流到哪一支」。{@code searchProvider(...)} 的分流逻辑本身不依赖容器，直接调用它 反而更准——起上下文再断言 Bean 类型，验到的是「同一件事绕了一圈」。
   */

  /**
   * 第 24 节 {@code WhitelistSandbox} 的替身：只是「容器里存在一个同类型 Bean」这件事本身。
   *
   * <p>用具体类而不是匿名类或 {@code @Primary}：匿名类无法 {@code isInstanceOf} 断言，{@code @Primary} 是「永远优先用假的」
   * ——正好反过来，真实实现会被永久遮蔽（contracts/assembly.md §6.2 / §6.4）。
   */
  @Configuration
  static class RealSandboxStandIn {

    @Bean
    Sandbox whitelistSandbox() {
      return new SandboxImpl();
    }

    /** 第 24 节真实实现的占位身份：本测试只关心「它存在」这件事。 */
    static final class SandboxImpl implements Sandbox {

      @Override
      public void enforce(SandboxAction action) {
        // 替身不做校验：本测试只验让位机制
      }
    }
  }
}
