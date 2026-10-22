package io.oryxlabs.oryxos.core.tool;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 承载「工具此刻在替哪个 Agent 干活」的 {@link ThreadLocal}（第 30 节 FR-007）。
 *
 * <p><b>为什么需要它、又为什么不改 {@code OryxTool} 签名</b>：工具要能把自己的记忆写进<b>这个 Agent 的</b> {@code MEMORY.md}，但
 * {@code OryxTool.execute(String)} 是骨架期定下的对外契约，改签名等于让每一档工具实现（含第三方 MCP 适配器）跟着返工。 同步阻塞模型下一次 ReAct
 * 循环整体跑在一条虚拟线程上，故「当前 Agent」可以由执行器在调用前后临时置入、ThreadLocal 天然不串号。
 *
 * <p><b>与 {@code ProfileContext} 的分工</b>：{@code ProfileContext} 是引擎层的「本次处理是哪个 Agent」，只由 {@code
 * AgentService} 放置；本类是工具层的「工具在替谁干活」，由 {@code ToolExecutor} 与记忆门面放置。两者都在 core、都不跨线程，但生命周期不同： 前者覆盖整轮
 * ReAct，后者只覆盖一次工具调用 / 一次记忆读取。
 *
 * <p><b>不变量</b>：{@link #callAs(String, Supplier)} 必须在 {@code finally}
 * 里<b>恢复旧值</b>而不是一律清空——本节调用不嵌套，但异常路径与将来加层都要求它可嵌套。裸 {@link #set(String)} 的调用方自己负责配对清理。
 */
public final class ToolExecutionContext {

  private static final ThreadLocal<String> CURRENT_AGENT = new ThreadLocal<>();

  private ToolExecutionContext() {}

  /**
   * 置入当前 Agent 名。
   *
   * @param agentName Agent 名；传 {@code null} 等同 {@link #clear()}（「没有上下文」与「上下文是空串」是同一件事，不留一个空串状态）
   */
  public static void set(String agentName) {
    if (agentName == null || agentName.isBlank()) {
      CURRENT_AGENT.remove();
      return;
    }
    CURRENT_AGENT.set(agentName);
  }

  /**
   * @return 当前正在替其干活的 Agent 名；未设置返回 {@code null}
   */
  public static String current() {
    return CURRENT_AGENT.get();
  }

  /** 清掉当前 Agent 名。 */
  public static void clear() {
    CURRENT_AGENT.remove();
  }

  /**
   * 在「当前 Agent = {@code agentName}」的上下文里执行一段动作，结束后<b>恢复</b>原来的值。
   *
   * <p>{@code finally} 里恢复旧值而不是清空：可嵌套，且异常路径同样复原——泄漏的上下文会让下一个复用该线程的调用读到上一个 Agent， 这类 bug
   * 在单线程测试里永远不报错。
   *
   * @param agentName 这段动作期间要置入的 Agent 名
   * @param action 动作
   * @param <T> 返回值类型
   * @return 动作的返回值
   */
  public static <T> T callAs(String agentName, Supplier<T> action) {
    Objects.requireNonNull(action, "action");
    String previous = CURRENT_AGENT.get();
    set(agentName);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        CURRENT_AGENT.remove();
      } else {
        CURRENT_AGENT.set(previous);
      }
    }
  }
}
