package io.oryxlabs.oryxos.tool.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxlabs.oryxos.tool.search.SearchProvider.SearchResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code web_search} 的四条守点（contracts/builtin-tools.md §2.10 / §3.4）。
 *
 * <p><b>接缝用替身，不打外网</b>（SC-008）：本类要证的是「工具怎么用接缝」（查询原样透传、上限 200、不可达分档）， 不是「DuckDuckGo 今天返回了什么」——后者是
 * {@code DuckDuckGoSearchProviderTest} 的活。
 *
 * <p><b>越界用例必须同时断言 provider 一次都没被调用</b>：只断言「抛了异常」的话，先搜索再校验的实现照样会绿。
 */
@ExtendWith(MockitoExtension.class)
class WebSearchToolsTest {

  private static final String ENDPOINT = "https://search.example.invalid/";

  @Mock private Sandbox sandbox;

  @Mock private SearchProvider provider;

  private WebSearchTools tools() {
    return new WebSearchTools(sandbox, provider);
  }

  @Test
  @DisplayName("正常路径：查询原样透传、上限 200，结果渲染成标题 + 链接 + 摘要")
  void webSearch_forwardsQueryVerbatim_andPassesResultLimit() {
    when(provider.endpoint()).thenReturn(ENDPOINT);
    when(provider.search(any(), anyInt()))
        .thenReturn(List.of(new SearchResult("杭州天气", "https://example.invalid/hz", "多云 22℃")));

    ToolResult result = tools().webSearch("杭州 天气 2026");

    assertThat(result.success()).isTrue();
    assertThat(result.content())
        .contains("杭州天气")
        .contains("https://example.invalid/hz")
        .contains("多云 22℃");

    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(provider).search(query.capture(), limit.capture());
    assertThat(query.getValue()).isEqualTo("杭州 天气 2026");
    assertThat(limit.getValue()).isEqualTo(200);
  }

  @Test
  @DisplayName("搜到了但没有结果：是成功（内容为「未找到结果」），不是失败")
  void webSearch_noResults_isSuccessNotFailure() {
    when(provider.endpoint()).thenReturn(ENDPOINT);
    when(provider.search(any(), anyInt())).thenReturn(List.of());

    ToolResult result = tools().webSearch("一个必定没有结果的词");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("未找到结果");
  }

  @Test
  @DisplayName("越界域名：被拦下，且搜索引擎一次都没被调用")
  void webSearch_outOfWhitelistDomain_isBlockedBeforeSearch() {
    when(provider.endpoint()).thenReturn(ENDPOINT);
    doThrow(new SandboxViolationException("越界: " + ENDPOINT)).when(sandbox).enforce(any());

    assertThatThrownBy(() -> tools().webSearch("杭州天气"))
        .isInstanceOf(SandboxViolationException.class);

    // 校验的目标 URL 来自接缝本身（不是装配处另传的字符串），且它确实是首行动作
    verify(sandbox).enforce(new SandboxAction(ActionType.HTTP_REQUEST, ENDPOINT));
    verify(provider, never()).search(any(), anyInt());
  }

  @Test
  @DisplayName("搜索引擎不可达：可重试失败")
  void webSearch_providerUnreachable_isRetryableFailure() {
    when(provider.endpoint()).thenReturn(ENDPOINT);
    when(provider.search(any(), anyInt()))
        .thenThrow(new UncheckedIOException(new IOException("connection refused")));

    ToolResult result = tools().webSearch("杭州天气");

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.errorMessage()).contains("搜索失败");
  }

  @Test
  @DisplayName("结果刚好到 200 条上限：照常返回，并注明可能还有更多")
  void webSearch_resultsAtLimit_carriesTruncationNote() {
    when(provider.endpoint()).thenReturn(ENDPOINT);
    when(provider.search(any(), anyInt()))
        .thenReturn(
            IntStream.rangeClosed(1, 200)
                .mapToObj(i -> new SearchResult("标题" + i, "https://example.invalid/" + i, "摘要" + i))
                .toList());

    ToolResult result = tools().webSearch("杭州天气");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("200. 标题200").contains("已达上限 200 条");
  }
}
