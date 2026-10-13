package io.oryxlabs.oryxos.provider;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把 OryxOS 的 {@link OryxTool} 翻译成 Spring AI 的 {@link ToolCallback}（技术方案 3.3）。
 *
 * <p><b>只翻译，不执行</b>（章程原则 III、FR-007）：产物里只带工具说明（名称 / 描述 / 入参 schema）， {@code call(String)} 一律抛
 * {@link UnsupportedOperationException}。真正执行工具的是上层（第 17 节的 {@code
 * ToolExecutor}），本模块一次都不执行——模型说"想调某工具"时，那个意图原样交回上层。
 *
 * <p>为什么要留一个抛错的 {@code call}：{@code ToolCallback} 接口强制实现它，用明确抛错来占位，把
 * "本模块不执行工具"这条约束变成代码里看得见、测试里断言得到的事实，而不是一句口头约定。
 */
public class ToolSchemaAdapter {

  /**
   * 逐项翻译工具说明。
   *
   * @param tools 本次可用工具；为空或 {@code null} 时返回空列表，不报错（spec.md 边界情形）
   * @return 与入参一一对应的 {@link ToolCallback}，顺序保持一致
   */
  public List<ToolCallback> toToolCallbacks(List<OryxTool> tools) {
    if (tools == null || tools.isEmpty()) {
      return List.of();
    }
    return tools.stream().map(ToolSchemaAdapter::toToolCallback).toList();
  }

  private static ToolCallback toToolCallback(OryxTool tool) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .inputSchema(tool.getInputSchema())
            .build();
    return new SchemaOnlyToolCallback(definition);
  }

  /** 只承载说明、不承载执行的 {@link ToolCallback}。 */
  private record SchemaOnlyToolCallback(ToolDefinition definition) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String toolInput) {
      throw new UnsupportedOperationException(
          "Provider 模块只翻译工具说明、不执行工具：'" + definition.name() + "' 的执行由上层 ToolExecutor 负责");
    }
  }
}
