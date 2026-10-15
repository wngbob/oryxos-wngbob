package io.oryxlabs.oryxos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * {@link ToolRegistry} 的守点：三源统一身份、过滤精确匹配、重名与未注册名的处理。
 *
 * <p><b>过滤那条用集合相等而不是 {@code containsAll}</b>：{@code containsAll} 在「多注册了一个」时<b>照样通过</b>，
 * 而「多一个」正是没过滤干净这个坏法。FR-005 要的是恰好相等，断言就必须写成相等。
 */
class ToolRegistryTest {

  /** 最小工具替身：只带名字，行为不重要。 */
  private static OryxTool stub(String name) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name + " 的用途";
      }

      @Override
      public String getInputSchema() {
        return "{}";
      }

      @Override
      public ToolResult execute(String inputJson) {
        return ToolResult.success(name);
      }
    };
  }

  private static Profile profileWith(String name, List<String> tools) {
    return new Profile(
        name,
        "测试用 Agent",
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  @Test
  @DisplayName("三种来源都以统一抽象身份注册，source 分别是 builtin / annotation / mcp:<server>")
  void threeSourcesRegisterWithUnifiedIdentityAndSourceLabel() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("read_file"), "builtin");
    registry.registerAnnotated(new WeatherTools(), "annotation");
    registry.register(stub("gh_list_prs"), "mcp:github-mcp");

    assertThat(registry.registered())
        .extracting(entry -> entry.tool().getName(), ToolRegistry.RegisteredTool::source)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("read_file", "builtin"),
            org.assertj.core.groups.Tuple.tuple("weather", "annotation"),
            org.assertj.core.groups.Tuple.tuple("gh_list_prs", "mcp:github-mcp"));

    // 三者取出来都是 OryxTool——调用方不需要知道它原本是什么
    assertThat(registry.find("read_file")).isPresent();
    assertThat(registry.find("weather")).isPresent();
    assertThat(registry.find("gh_list_prs")).isPresent();
  }

  @Test
  @DisplayName("按 Agent 声明过滤后子集恰好相等，不多不少")
  void availableForMatchesDeclaredSetExactly() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("read_file"), "builtin");
    registry.register(stub("write_file"), "builtin");
    registry.register(stub("shell"), "builtin");

    List<OryxTool> available =
        registry.availableFor(profileWith("demo", List.of("read_file", "shell")));

    // 用集合相等：containsAll 在「多给了一个」时会假绿
    assertThat(available).extracting(OryxTool::getName).containsExactly("read_file", "shell");
  }

  @Test
  @DisplayName("过滤结果按声明顺序，不按注册顺序")
  void availableForFollowsDeclarationOrder() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("a"), "builtin");
    registry.register(stub("b"), "builtin");
    registry.register(stub("c"), "builtin");

    assertThat(registry.availableFor(profileWith("demo", List.of("c", "a"))))
        .extracting(OryxTool::getName)
        .containsExactly("c", "a");
  }

  @Test
  @DisplayName("声明了但未注册的工具名：跳过，不抛异常，结果里不含它")
  void declaredButUnregisteredNameIsSkipped() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("read_file"), "builtin");

    List<OryxTool> available =
        registry.availableFor(profileWith("demo", List.of("read_file", "not_a_tool")));

    assertThat(available).extracting(OryxTool::getName).containsExactly("read_file");
  }

  @Test
  @DisplayName("Profile 未声明任何工具：返回空列表，不是错误")
  void emptyDeclaredToolsYieldsEmptyList() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("read_file"), "builtin");

    assertThat(registry.availableFor(profileWith("demo", List.of()))).isEmpty();
    assertThat(registry.availableFor(null)).isEmpty();
  }

  @Test
  @DisplayName("重名：保留先注册者，后到者被丢弃")
  void duplicateNameKeepsFirstRegistered() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("http_get"), "builtin");
    registry.register(stub("http_get"), "mcp:evil-server");

    assertThat(registry.registered()).hasSize(1);
    assertThat(registry.registered().get(0).source()).isEqualTo("builtin");
  }

  @Test
  @DisplayName("内置先注册、MCP 后注册：重名时内置胜出")
  void builtinWinsOverMcpOnCollision() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("notify"), "builtin");
    registry.register(stub("notify"), "mcp:notify-server");

    assertThat(registry.registered())
        .extracting(ToolRegistry.RegisteredTool::source)
        .containsExactly("builtin");
  }

  @Test
  @DisplayName("工具名为空白或工具为 null：注册时就报错，不静默")
  void invalidRegistrationFailsFast() {
    ToolRegistry registry = new ToolRegistry();

    assertThatThrownBy(() -> registry.register(null, "builtin"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.register(stub("  "), "builtin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.registerAnnotated(null, "annotation"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("registerAnnotated：只扫带 @Tool 的方法，且扫到空不报错")
  void registerAnnotatedScansOnlyAnnotatedMethods() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new WeatherTools(), "annotation");

    assertThat(registry.registered())
        .extracting(entry -> entry.tool().getName())
        .containsExactly("weather");
    assertThat(registry.contains("notATool")).isFalse();

    // 业务方还没写工具是正常状态
    ToolRegistry empty = new ToolRegistry();
    empty.registerAnnotated(new Object(), "annotation");
    assertThat(empty.registered()).isEmpty();
  }

  @Test
  @DisplayName("contains 与 find 对未知名字给出一致的否定结论")
  void containsAndFindAgreeOnUnknownName() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(stub("read_file"), "builtin");

    assertThat(registry.contains("read_file")).isTrue();
    assertThat(registry.contains("nope")).isFalse();
    assertThat(registry.find("nope")).isEmpty();
    assertThat(registry.find(null)).isEmpty();
  }

  /** 业务方自己写的工具 Bean（方式三），用来证「注解来源」这一档。 */
  static class WeatherTools {

    @Tool(description = "查天气")
    public String weather(String city) {
      return city + " 晴";
    }

    public String notATool() {
      return "不应注册";
    }
  }
}
