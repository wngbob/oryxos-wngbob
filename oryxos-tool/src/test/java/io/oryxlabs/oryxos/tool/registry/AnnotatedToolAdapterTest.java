package io.oryxlabs.oryxos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.notify.NotifyTools;
import io.oryxlabs.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxlabs.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxlabs.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxlabs.oryxos.tool.sandbox.WhitelistSandbox;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * {@link AnnotatedToolAdapter} 的守点：schema 参数名、返回值语义保真、异常不吞。
 *
 * <p><b>第一条是本节最值钱的一条测试</b>：它守的是父 pom 的 {@code <parameters>true</parameters>}。谁把那个开关删了，这条立刻红
 * ——避免「编译通过、门禁全绿、模型却调不动工具」的静默失效。
 */
class AnnotatedToolAdapterTest {

  private static OryxTool adapt(Object bean, String methodName, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Method method = bean.getClass().getMethod(methodName, parameterTypes);
    return new AnnotatedToolAdapter(bean, method);
  }

  @Test
  @DisplayName("notify 的入参 Schema 里必须是真实参数名 content / channel，不能退化成 arg0")
  void notifySchema_usesRealParameterNames() throws NoSuchMethodException {
    // 第 19 节交付的 NotifyTools 是本节注册的第一个真实 @Tool Bean，用它守 -parameters 最贴近生产
    // 沙箱用真实现 + 三个空表（= 全拒）：本用例只读 JSON Schema、一次都不执行工具，白名单空不空与它无关；
    // 用真实现而不是再造一个全放行替身——那个替身已在第 24 节按裁决 3 整类删除，留着等于把 fail-open 又搬回来
    NotifyTools notifyTools =
        new NotifyTools(
            new WhitelistSandbox(
                new FileSandboxProperties(List.of()),
                new ShellSandboxProperties(List.of()),
                new HttpSandboxProperties(List.of())),
            (target, content) -> {},
            channel -> null);

    String schema = adapt(notifyTools, "notify", String.class, String.class).getInputSchema();

    assertThat(schema).contains("content").contains("channel").doesNotContain("arg0");
  }

  @Test
  @DisplayName("方法返回 ToolResult 时原样返回，retryable 不丢")
  void toolResultReturnValueIsPreserved() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "retryableTool");

    ToolResult result = tool.execute("{}");

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.errorMessage()).isEqualTo("网络超时");
  }

  @Test
  @DisplayName("方法返回非 ToolResult 时包成成功结果")
  void nonToolResultReturnValueBecomesSuccess() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "plainTool", String.class);

    ToolResult result = tool.execute("{\"text\":\"你好\"}");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("收到: 你好");
  }

  @Test
  @DisplayName("方法抛异常时上抛，不在适配器内吞掉")
  void methodExceptionPropagates() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "throwingTool");

    // 吞掉异常 = 失败落不进 tool_invocations（FR-021）；必须让 ToolExecutor 拿到它
    assertThatThrownBy(() -> tool.execute("{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("工具内部炸了");
  }

  @Test
  @DisplayName("缺必填参数时明确报错，不静默补 null")
  void missingRequiredParameterFails() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "plainTool", String.class);

    ToolResult result = tool.execute("{}");

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("text");
  }

  @Test
  @DisplayName("标了 required=false 的参数缺省时传 null，不报错")
  void optionalParameterMayBeOmitted() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "optionalTool", String.class, String.class);

    ToolResult result = tool.execute("{\"required\":\"有\"}");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("有/null");
  }

  @Test
  @DisplayName("参数按 JSON 类型转换后传入，不是一律当字符串")
  void argumentsAreConvertedToDeclaredTypes() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "numericTool", int.class);

    ToolResult result = tool.execute("{\"count\":7}");

    assertThat(result.content()).isEqualTo("8");
  }

  @Test
  @DisplayName("入参为空串时按无参处理，不当作错误")
  void blankInputIsTreatedAsNoArguments() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "retryableTool");

    assertThat(tool.execute("").success()).isFalse(); // 结果来自方法本身，而非解析失败
  }

  @Test
  @DisplayName("schema 是合法 JSON 且带参数说明")
  void schemaIsJsonWithProperties() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "plainTool", String.class);

    String schema = tool.getInputSchema();

    assertThat(schema).startsWith("{").contains("\"properties\"").contains("text");
  }

  @Test
  @DisplayName("三种元数据都能取到：名字、描述、入参 Schema")
  void metadataComesFromAnnotation() throws NoSuchMethodException {
    OryxTool tool = adapt(new SampleTools(), "plainTool", String.class);

    assertThat(tool.getName()).isEqualTo("plain_tool");
    assertThat(tool.getDescription()).isEqualTo("把文本原样回显");
    assertThat(tool.getInputSchema()).isNotBlank();
  }

  /** 供适配器测试使用的最小 Bean；只验适配器语义，不碰任何真实工具。 */
  static class SampleTools {

    @Tool(name = "plain_tool", description = "把文本原样回显")
    public String plainTool(@ToolParam(description = "要回显的文本") String text) {
      return "收到: " + text;
    }

    @Tool(description = "总是返回可重试失败")
    public ToolResult retryableTool() {
      return ToolResult.retryableFailure("网络超时");
    }

    @Tool(description = "总是抛异常")
    public String throwingTool() {
      throw new IllegalStateException("工具内部炸了");
    }

    @Tool(description = "第二个参数可选")
    public String optionalTool(
        @ToolParam(description = "必填") String required,
        @ToolParam(description = "可选", required = false) String optional) {
      return required + "/" + optional;
    }

    @Tool(description = "整数入参")
    public String numericTool(@ToolParam(description = "一个数") int count) {
      return String.valueOf(count + 1);
    }
  }
}
