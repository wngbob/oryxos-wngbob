package io.oryxlabs.oryxos.memory;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.tool.ToolExecutionContext;
import io.oryxlabs.oryxos.memory.store.LongTermMemoryStore;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import io.oryxlabs.oryxos.memory.store.MemoryScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 门面唯一实现：把三类操作转发给选中的那一档后端，外加一处判断——<b>按后端的检索能力路由 {@code recall}</b>。
 *
 * <p><b>为什么不缓存 {@code load()} 的结果</b>：门面一旦缓存，Agent 调完 {@code save_memory} 后下一轮就看不见自己刚记的东西，
 * 而「记了却想不起来」是最难查的一类问题。后端每档都保证「每次重新读」，门面这一层再缓存等于把三档的承诺一次作废。
 *
 * <p><b>{@code longTermMemory} 的 {@code profile} 参数</b>：第 30 节起用于置入「这次读的是哪个 Agent 的记忆」（{@link
 * ToolExecutionContext}），Markdown 档据此把落点从全局 {@code memory/MEMORY.md} 换到 {@code
 * agents/<name>/MEMORY.md}。 <b>签名一字未改</b>——那是 core 侧既有接缝；SQLite / Mem0 两档仍全局单份，不按 Agent 隔离。
 */
public class MemoryServiceImpl implements MemoryService {

  /** 后端。构造后不再变，故无需同步。 */
  private final LongTermMemoryStore store;

  public MemoryServiceImpl(LongTermMemoryStore store) {
    this.store = Objects.requireNonNull(store, "长期记忆后端不能为空");
  }

  /**
   * 取跨会话的长期记忆文本（{@link MemorySource} 接缝的实现）。
   *
   * <p><b>{@code profile} 参数用于选定记忆落点</b>：本方法在 {@link ToolExecutionContext#callAs} 里委托后端，Markdown
   * 档据此读写 {@code agents/<name>/MEMORY.md}；无 Agent 上下文时回退全局文件。
   *
   * <p><b>MUST NOT 混入会话历史</b>：那是两件生命周期完全不同的事（长期记忆跨会话都在，会话历史只属于这一次对话）， 混在一起返回会把它们焊死，也让 {@code
   * PromptBuilder} 无法独立决定各自怎么截断。
   *
   * @param profile 当前 Agent 的运行时配置；为 {@code null} 时按无上下文处理
   * @return 长期记忆文本；还没记过任何东西时返回 {@link Optional#empty()}，让 {@code PromptBuilder} 把 prompt 第二部分整条跳过
   */
  @Override
  public Optional<String> longTermMemory(Profile profile) {
    // 第 30 节 FR-007：置入「这次读的是哪个 Agent 的记忆」再委托后端，finally 复原。
    // profile 为空时 callAs(null) 等同无上下文（Markdown 档回退全局路径），与第 22 节行为一致
    String text = ToolExecutionContext.callAs(profile == null ? null : profile.name(), store::load);
    // 空白 = 还没记过任何东西：返回 empty 让 PromptBuilder 把 prompt 第二部分整条跳过，而不是塞一段空壳占上下文
    return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  @Override
  public void remember(String content, MemoryScope scope) {
    store.append(
        content, Objects.requireNonNull(scope, "scope 不能为空：缺省归档由 MemoryTools 决定，门面不替调用方猜"));
  }

  @Override
  public List<String> recall(String keyword) {
    switch (store.capabilities()) {
      case KEYWORD:
      case DELEGATED:
        // DELEGATED 档也走同一个方法：由那一档自己把查询转给外部服务，门面不多一条分支
        return store.recallByKeyword(keyword);
      case HYBRID_BUILTIN:
        throw new IllegalStateException(
            "长期记忆后端声明了 HYBRID_BUILTIN 检索能力，但核心阶段没有内置语义检索实现（那需要内置向量检索路径）"
                + "；请改用 KEYWORD 档，或切到 Mem0 这类 DELEGATED 档由外部服务承担语义检索");
      default:
        throw new IllegalStateException("未知的检索能力：" + store.capabilities());
    }
  }

  @Override
  public MemoryPartition partition() {
    // 与 longTermMemory 一样不缓存：管理台看到的必须是「此刻」的记忆
    return store.partition();
  }

  /**
   * 仅供本包内诊断用，<b>刻意不打印后端的任何配置</b>——Mem0 档的地址含 token。
   *
   * @return 固定文案
   */
  @Override
  public String toString() {
    return "MemoryServiceImpl";
  }
}
