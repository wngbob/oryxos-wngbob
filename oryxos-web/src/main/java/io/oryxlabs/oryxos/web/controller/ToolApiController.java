package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.ToolView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具列表端点（contracts/rest-endpoints.md §3.3）。
 *
 * <p><b>给的是全量注册表</b>，不做按 Agent 过滤：过滤需要 Profile 上下文（{@code ToolRegistry.availableFor}）， 属于「某个 Agent
 * 能用什么」，不是「底座装了什么」——管理台要回答的是后者。
 *
 * <p><b>{@code source} 是注册关系的属性</b>，所以从 {@code registered()} 取而不是 {@code
 * ToolCatalog}：后者只有工具本身，没有来源。
 */
@RestController
@RequestMapping("/api/v1/tools")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 26 节交付的对外入口本身（FR-001），不是缺陷")
public class ToolApiController {

  /** MCP 来源的前缀（{@code mcp:<server>}）。 */
  private static final String MCP_PREFIX = "mcp:";

  private final ToolRegistry toolRegistry;

  /**
   * @param toolRegistry 三种来源（内置 / 注解 / MCP）的共同归宿
   */
  public ToolApiController(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  /**
   * 列出全部已注册工具。
   *
   * @return 工具视图，{@code origin} 反映来源
   */
  @GetMapping
  public ApiResponse<List<ToolView>> list() {
    List<ToolView> views =
        toolRegistry.registered().stream().map(ToolApiController::toView).toList();
    return ApiResponse.ok(views);
  }

  private static ToolView toView(ToolRegistry.RegisteredTool entry) {
    String source = entry.source();
    String origin =
        source != null && source.startsWith(MCP_PREFIX)
            ? source.substring(MCP_PREFIX.length())
            : source;
    OryxTool tool = entry.tool();
    return new ToolView(
        tool.getName(), tool.getDescription(), tool.getInputSchema(), source, origin);
  }
}
