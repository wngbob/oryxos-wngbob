package io.oryxlabs.oryxos.memory.builtin;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.store.MemoryScope;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 两个内置记忆工具：让 Agent 自己决定「什么值得记」和「想起什么」。
 *
 * <p><b>写入时机永远由 Agent 判断</b>：核心阶段不做自动提炼——从对话里自动抽记忆需要另一套模型调用与冲突消解，而且抽错了用户根本不知道。 Agent 主动调 {@code
 * save_memory} 时，用户在同一段对话里就能看见它记了什么，错了当场纠正。
 *
 * <p><b>工具名用 {@code @Tool(name = ...)} 显式钉死</b>：工具名是模型引用它的唯一凭据，一旦交付不得改。显式钉名还有一层——方法名与工具名解耦，
 * 重命名方法不会静默改掉工具名（第 19 节 {@code notify} 起就是这么做的）。
 *
 * <p><b>{@code scope} 必须带 {@code @ToolParam(required = false)}</b>：Spring AI 的 {@code
 * JsonSchemaGenerator} 对没有 注解的参数一律判为必填，不声明的话模型看到的 Schema 会把 {@code scope} 列进 {@code
 * required}，「不确定就填 archival」在模型可见层 根本不成立。
 *
 * <p><b>非法 scope 抛异常而不是返回失败结果</b>：抛出去才会让这次调用在 {@code tool_invocations} 里被记成失败、并把原因回灌给模型；
 * 返回一段错误文案会被审计记成 {@code success=true}——审计表说谎比工具报错严重得多。
 */
public class MemoryTools {

  /** 工具名，模型引用它的唯一凭据。 */
  private static final String SAVE_MEMORY = "save_memory";

  /** 工具名，模型引用它的唯一凭据。 */
  private static final String RECALL_MEMORY = "recall_memory";

  /** 未命中时的返回文案，**逐字钉死**（FR-013）。 */
  static final String NO_MEMORY_FOUND = "没有找到相关记忆";

  private final MemoryService memoryService;

  public MemoryTools(MemoryService memoryService) {
    this.memoryService = Objects.requireNonNull(memoryService, "记忆门面不能为空");
  }

  /**
   * 记住一件事。
   *
   * @param content 要记住的内容
   * @param scope 分区；不传或传空白按归档区处理
   * @return 成功结果
   */
  @Tool(name = SAVE_MEMORY, description = "记住一件值得长期记住的事")
  public ToolResult saveMemory(
      @ToolParam(description = "要记住的内容") String content,
      @ToolParam(description = "core 或 archival，不确定就填 archival", required = false) String scope) {
    memoryService.remember(content, parseScope(scope));
    return ToolResult.success("已记住");
  }

  /**
   * 想起一件事。
   *
   * <p><b>未命中返回成功结果而不是失败</b>：查不到是正常结果，不是故障。抛异常或返回失败会让这一轮对话中断、并在 {@code tool_invocations}
   * 里留下一条假故障——而「这件事我没记过」本身正是模型需要知道的信息。
   *
   * @param keyword 检索关键词
   * @return 命中条目的拼接；未命中返回固定文案
   */
  @Tool(name = RECALL_MEMORY, description = "按关键词检索长期记忆")
  public ToolResult recallMemory(@ToolParam(description = "检索关键词") String keyword) {
    List<String> hits = memoryService.recall(keyword);
    if (hits.isEmpty()) {
      return ToolResult.success("没有找到相关记忆");
    }
    return ToolResult.success(String.join("\n", hits));
  }

  /**
   * 把模型递进来的 scope 字符串翻译成分区。
   *
   * <p><b>MUST NOT {@code trim()}</b>：{@code "CORE "}（带尾空格）是<b>非法值</b>，必须报错。顺手 trim
   * 一下看着友好，实际是把「模型拼错了」 悄悄变成「模型拼对了」——而拼错的模式一旦被容忍，下一次它可能拼成别的、也就没人发现。
   *
   * @param scope 模型给的分区字符串，可为 null
   * @return 分区，null / 空白 → 归档区
   */
  private static MemoryScope parseScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return MemoryScope.ARCHIVAL;
    }
    try {
      return MemoryScope.valueOf(scope.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("非法的 scope 值 \"" + scope + "\"（合法取值：CORE 或 ARCHIVAL）", e);
    }
  }
}
