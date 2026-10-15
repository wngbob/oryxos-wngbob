package io.oryxlabs.oryxos.tool.search;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置搜索工具：{@code web_search}（contracts/builtin-tools.md §2.10；FR-005 / FR-018）。
 *
 * <p><b>工具不认识任何一家搜索引擎</b>：查询、条数上限、目标 URL 全部来自注入的 {@link SearchProvider}。换 Google / Bing / Tavily
 * 只加一个实现类——这个文件与调用方一行不改，这正是 FR-018 的验收方式。
 *
 * <p><b>沙箱校验的目标 URL 取自 {@code provider.endpoint()}</b>：{@code web_search} 自己没有 URL 可校验，而「校验位不因加工具
 * 而改动」是 FR-016 的要求。让 URL 长在接缝上（见 {@link SearchProvider#endpoint()}），装配处就不必为换引擎再传一个字符串。
 *
 * <p><b>「没搜到」与「没搜成」是两件事</b>：前者是正常的空结果，返回成功（内容为「未找到结果」），模型据此换个说法再试；
 * 后者是网络问题，返回<b>可重试</b>失败。把两者都做成失败，会让模型对「这个词确实没有结果」也反复重试。
 */
public class WebSearchTools {

  /**
   * 结果条数上限（contracts/builtin-tools.md §2.10 / FR-006）。超了截断并注明——一次把几百条摘要塞进上下文会把模型的窗口撑爆。
   *
   * <p>与 {@code FileTools} 里同名同值的那一份是<b>刻意各留一份</b>：两者是不同工具的独立上限，抽成共享常量就得新开一个 public 类型，
   * 而它不在本节交付物清单里（软门禁 ①）。
   */
  private static final int MAX_RESULTS = 200;

  private final Sandbox sandbox;

  private final SearchProvider provider;

  public WebSearchTools(Sandbox sandbox, SearchProvider provider) {
    this.sandbox = sandbox;
    this.provider = provider;
  }

  /**
   * 用搜索引擎检索网页。
   *
   * @param query 查询词
   * @return 有结果时为「标题 + 链接 + 摘要」清单；搜到了但没结果为成功（内容为「未找到结果」）；搜索引擎不可达为可重试失败
   */
  @Tool(name = "web_search", description = "用搜索引擎检索网页，返回标题、链接与摘要")
  public ToolResult webSearch(@ToolParam(description = "查询词") String query) {
    // 首行过白名单：校验不通过时一次请求都不发
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, provider.endpoint()));

    List<SearchProvider.SearchResult> results;
    try {
      results = provider.search(query, MAX_RESULTS);
    } catch (UncheckedIOException e) {
      return ToolResult.retryableFailure("搜索失败: " + e.getMessage());
    }

    if (results == null || results.isEmpty()) {
      return ToolResult.success("未找到结果");
    }
    return ToolResult.success(render(results));
  }

  private static String render(List<SearchProvider.SearchResult> results) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < results.size(); i++) {
      SearchProvider.SearchResult result = results.get(i);
      builder
          .append(i + 1)
          .append(". ")
          .append(result.title())
          .append('\n')
          .append(result.url())
          .append('\n')
          .append(result.snippet())
          .append('\n');
    }
    if (results.size() >= MAX_RESULTS) {
      builder.append("（已达上限 ").append(MAX_RESULTS).append(" 条，可能有更多结果未显示）\n");
    }
    return builder.toString().stripTrailing();
  }
}
