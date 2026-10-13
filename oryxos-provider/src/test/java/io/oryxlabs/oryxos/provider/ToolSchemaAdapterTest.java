package io.oryxlabs.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class ToolSchemaAdapterTest {

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

  private final ToolSchemaAdapter adapter = new ToolSchemaAdapter();

  /** 测试替身：只提供三项说明，execute 不被本节的适配器调用。 */
  private static OryxTool oryxTool(String name, String description, String schema) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return description;
      }

      @Override
      public String getInputSchema() {
        return schema;
      }

      @Override
      public ToolResult execute(String inputJson) {
        throw new UnsupportedOperationException("测试替身不执行工具");
      }
    };
  }

  @Test
  @DisplayName("schema 字段一一对齐")
  void translatesSchemaFieldsVerbatim() {
    List<ToolCallback> callbacks =
        adapter.toToolCallbacks(List.of(oryxTool("read_file", "读一个文件", SCHEMA)));

    assertThat(callbacks).hasSize(1);
    var definition = callbacks.get(0).getToolDefinition();
    assertThat(definition.name()).isEqualTo("read_file");
    assertThat(definition.description()).isEqualTo("读一个文件");
    assertThat(definition.inputSchema()).isEqualTo(SCHEMA);
  }

  @Test
  @DisplayName("只翻译：产物里不含任何执行逻辑")
  void callbacksCarryNoExecution() {
    List<ToolCallback> callbacks =
        adapter.toToolCallbacks(List.of(oryxTool("read_file", "读一个文件", SCHEMA)));

    assertThatThrownBy(() -> callbacks.get(0).call("{\"path\":\"/tmp/a\"}"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("工具说明为空：返回空列表不报错")
  void emptyToolsYieldEmptyList() {
    assertThat(adapter.toToolCallbacks(List.of())).isEmpty();
  }
}
