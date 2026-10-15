package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由接缝的验收 harness。
 *
 * <p><b>这里注入的是替身</b>：本类要证的是「哪个 type 落到哪一档」这件事本身，跟某一档怎么发 HTTP 无关。真实实现的分派效果由 {@code
 * FeishuNotifyToolTest} 端到端覆盖。
 */
class RoutingNotifyChannelAdapterTest {

  private static NotifyTarget targetOf(String type) {
    return new NotifyTarget(type, Map.of("url", "https://example.invalid/hook"));
  }

  /** 装配处给出的那张表的测试版：三档取值、两个实现，别名与取值一样留在装配侧。 */
  private static RoutingNotifyChannelAdapter router(
      NotifyChannelAdapter dialect, NotifyChannelAdapter generic) {
    Map<String, NotifyChannelAdapter> byType = new LinkedHashMap<>();
    byType.put("webhook", generic);
    byType.put("feishu", dialect);
    byType.put("lark", dialect);
    return new RoutingNotifyChannelAdapter(byType);
  }

  @Test
  @DisplayName("feishu 与 lark 都落到方言档，通用档一次都不被调用")
  void dispatchesDialectTypesToTheDialectAdapter() {
    NotifyChannelAdapter dialect = mock(NotifyChannelAdapter.class);
    NotifyChannelAdapter generic = mock(NotifyChannelAdapter.class);
    RoutingNotifyChannelAdapter router = router(dialect, generic);

    router.send(targetOf("feishu"), "内容");
    router.send(targetOf("lark"), "内容");

    verify(dialect, times(2)).send(any(NotifyTarget.class), anyString());
    verify(generic, never()).send(any(NotifyTarget.class), anyString());
  }

  @Test
  @DisplayName("webhook 落到通用档")
  void dispatchesGenericTypeToTheGenericAdapter() {
    NotifyChannelAdapter dialect = mock(NotifyChannelAdapter.class);
    NotifyChannelAdapter generic = mock(NotifyChannelAdapter.class);

    router(dialect, generic).send(targetOf("webhook"), "内容");

    verify(generic, times(1)).send(any(NotifyTarget.class), anyString());
    verify(dialect, never()).send(any(NotifyTarget.class), anyString());
  }

  @Test
  @DisplayName("type 带空白或大小写不一时归一后再查表——那是人手写进注册表的值")
  void trimsAndLowercasesChannelTypeBeforeLookup() {
    NotifyChannelAdapter dialect = mock(NotifyChannelAdapter.class);

    RoutingNotifyChannelAdapter router = router(dialect, mock(NotifyChannelAdapter.class));
    router.send(targetOf("  Feishu  "), "内容");
    router.send(targetOf("LARK"), "内容");

    verify(dialect, times(2)).send(any(NotifyTarget.class), anyString());
  }

  @Test
  @DisplayName("未知 type 抛异常并列出已知类型（排序后，文案不随 Map 迭代顺序抖动）")
  void throwsOnUnknownTypeAndListsKnownTypesSorted() {
    RoutingNotifyChannelAdapter router =
        router(mock(NotifyChannelAdapter.class), mock(NotifyChannelAdapter.class));

    assertThatThrownBy(() -> router.send(targetOf("feshu"), "内容"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("feshu")
        .hasMessageContaining("feishu, lark, webhook");
  }

  @Test
  @DisplayName("未知 type 时一次都不委派——拼错 type 不能悄悄退回某一档")
  void doesNotDelegateOnUnknownType() {
    NotifyChannelAdapter dialect = mock(NotifyChannelAdapter.class);
    NotifyChannelAdapter generic = mock(NotifyChannelAdapter.class);

    assertThatThrownBy(() -> router(dialect, generic).send(targetOf("feshu"), "内容"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(dialect, never()).send(any(NotifyTarget.class), anyString());
    verify(generic, never()).send(any(NotifyTarget.class), anyString());
  }

  @Test
  @DisplayName("type 为 null 或空白时给明确报错，而不是 NPE")
  void throwsClearErrorForNullOrBlankChannelType() {
    RoutingNotifyChannelAdapter router =
        router(mock(NotifyChannelAdapter.class), mock(NotifyChannelAdapter.class));

    assertThatThrownBy(() -> router.send(targetOf(null), "内容"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type 为空");
    assertThatThrownBy(() -> router.send(targetOf("   "), "内容"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type 为空");
  }

  @Test
  @DisplayName("映射表为空时任何 type 都抛，不会假装送达")
  void throwsForEveryTypeWhenMapIsEmpty() {
    RoutingNotifyChannelAdapter empty = new RoutingNotifyChannelAdapter(Map.of());

    assertThatThrownBy(() -> empty.send(targetOf("webhook"), "内容"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的通知渠道类型");
  }

  @Test
  @DisplayName("每次 send 恰好委派一次，不重试不广播")
  void delegatesExactlyOncePerSend() {
    NotifyChannelAdapter dialect = mock(NotifyChannelAdapter.class);

    router(dialect, mock(NotifyChannelAdapter.class)).send(targetOf("feishu"), "内容");

    verify(dialect, times(1)).send(any(NotifyTarget.class), anyString());
  }

  @Test
  @DisplayName("构造时防御性拷贝：事后改动传入的 Map 不影响路由")
  void copiesTheMapDefensively() {
    NotifyChannelAdapter dialect = mock(NotifyChannelAdapter.class);
    Map<String, NotifyChannelAdapter> mutable = new HashMap<>();
    mutable.put("feishu", dialect);
    RoutingNotifyChannelAdapter router = new RoutingNotifyChannelAdapter(mutable);

    mutable.clear();

    router.send(targetOf("feishu"), "内容");
    verify(dialect, times(1)).send(any(NotifyTarget.class), anyString());
  }

  @Test
  @DisplayName("路由类源码里不含任何厂商字面量——厂商知识只允许留在装配处")
  void sourceHasNoVendorStringLiterals() throws IOException {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/io/oryxlabs/oryxos/tool/notify/RoutingNotifyChannelAdapter.java"),
            StandardCharsets.UTF_8);

    // 判据是「带引号的字面量」：javadoc 里为了讲清设计可以提到厂商名，但代码里出现就说明知识落错了层
    for (String vendor : new String[] {"feishu", "lark", "webhook", "wecom", "dingtalk"}) {
      assertThat(source).doesNotContain("\"" + vendor + "\"");
    }
  }
}
