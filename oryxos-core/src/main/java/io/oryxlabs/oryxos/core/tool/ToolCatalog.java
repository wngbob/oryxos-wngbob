package io.oryxlabs.oryxos.core.tool;

import io.oryxlabs.oryxos.core.profile.Profile;
import java.util.List;
import java.util.Optional;

/**
 * core 侧的「按名查工具 / 列可用工具」接缝（contracts/core-seams.md §2）。
 *
 * <p><b>实现方</b>：{@code oryxos-tool} 的 {@code ToolRegistry}（第 20 节）。它汇聚内置 Tool / MCP Tool /
 * {@code @Tool} Bean 三源，全部包装成统一的 {@link OryxTool}（章程原则 V）；{@code AGENT.md} MUST NOT 注册进来（章程原则 IV）。
 *
 * <p><b>为什么是两个方法而不是一个</b>：两处调用的过滤口径不同——{@link #find(String)} 在执行时按模型给的名字精确命中， {@link
 * #availableFor(Profile)} 在组装 prompt 时按 Profile 声明的子集列全量说明。合成一个方法会让两边各带一半参数。
 */
public interface ToolCatalog {

  /**
   * 按名取工具。
   *
   * @param name 模型请求的工具名
   * @return 命中即返回该工具，<b>查不到返回空</b>——由 {@code ToolExecutor} 转成「失败结果 + 落审计」，MUST NOT 抛异常打断循环
   */
  Optional<OryxTool> find(String name);

  /**
   * 本次处理可用的工具子集（按 Profile 的 {@code tools} 字段过滤后的结果）。
   *
   * @param profile 当前 Agent 的运行时配置
   * @return 可用工具；Profile 未声明工具清单时返回空表
   */
  List<OryxTool> availableFor(Profile profile);
}
