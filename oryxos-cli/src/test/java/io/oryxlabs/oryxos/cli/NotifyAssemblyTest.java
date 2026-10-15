package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxlabs.oryxos.tool.notify.NotifyTarget;
import io.oryxlabs.oryxos.tool.notify.RoutingNotifyChannelAdapter;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 通知渠道装配的硬约束（2026-10-15 补强引入路由接缝后新增，补强③ 扩充）。
 *
 * <p><b>为什么必须起真上下文</b>：本节新增的坏法只在真实启动流程里才现形——飞书档、企微档与通用档都实现 {@code NotifyChannelAdapter}，
 * 只要其中一个被单独声明成 {@code @Bean}，容器里就有两个同类型 Bean，{@code NotifyTools} 的注入当场变成 {@code
 * NoUniqueBeanDefinitionException}。手工 {@code new} 一个路由实例来断言，等于把这条绕过去。
 *
 * <p><b>断言是「恰好一个」而不是「存在一个是」</b>：后者在「两个都在」时照样绿，而那正是最危险的坏法。
 *
 * <p><b>第二条测的是映射表的内容，而不是 Bean 的数量</b>：方言档在方法体内 {@code new} 出来交给路由持有，容器里看不见它们，所以
 * 「企微那一档到底挂上没有」只能从<b>路由的行为</b>上观察——拿一个不存在的 type 去 {@code send}，报错文案会列出已知类型。这是从容器外部
 * 读到映射表内容的唯一途径，也顺带钉死了取值的拼法。
 */
class NotifyAssemblyTest {

  @Test
  @DisplayName("装配后容器里 NotifyChannelAdapter 类型的 Bean 恰好一个，且是路由实现")
  void notifyChannelAdapter_hasExactlyOneBean_andItIsTheRouter(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      Map<String, NotifyChannelAdapter> beans = context.getBeansOfType(NotifyChannelAdapter.class);

      // 「恰好一个」同时抓住两种坏法：某一档被单独声明成 Bean（两个）、路由压根没装配上（零个）
      assertThat(beans).hasSize(1);
      assertThat(beans.values().iterator().next()).isInstanceOf(RoutingNotifyChannelAdapter.class);
    }
  }

  @Test
  @DisplayName("装配后的映射表覆盖四个取值：webhook / feishu / lark / wecom（顺序按已知类型排序）")
  void routerKnowsEveryDialectType(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      NotifyChannelAdapter adapter = context.getBean(NotifyChannelAdapter.class);

      // type 未知 → 路由在发请求之前就报错并列出已知类型。用 example.invalid 是为了万一真走到网络也立刻失败，
      // 但这条路径**一次请求都不会发**（RoutingNotifyChannelAdapter 的既有守卫）。
      assertThatThrownBy(
              () ->
                  adapter.send(
                      new NotifyTarget("no-such-type", Map.of("url", "https://example.invalid/x")),
                      "内容"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("feishu, lark, webhook, wecom");
    }
  }
}
