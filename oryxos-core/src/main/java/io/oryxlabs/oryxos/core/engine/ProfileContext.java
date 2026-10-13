package io.oryxlabs.oryxos.core.engine;

import io.oryxlabs.oryxos.core.profile.Profile;

/**
 * 承载「本次处理是哪个 Agent」的 {@link ThreadLocal}（FR-009 / FR-010）。
 *
 * <p>生命周期与一次 {@code AgentService.process} 严格对齐：入口 {@link #set(Profile)}、{@code finally} 里 {@link
 * #clear()}。
 *
 * <p><b>为什么是 ThreadLocal 而不是参数</b>：{@code OryxTool.execute(String)} 的签名不带 Profile（章程原则 V， 本节 MUST
 * NOT 改签名）；虚拟线程下每请求独占线程，ThreadLocal 天然不串号。
 *
 * <p><b>不变量</b>：<b>任何</b>退出路径都必须让 {@link #current()} 回到 {@code null}。异常路径不清 = 下一个复用该线程的请求读到 上一个请求的
 * Agent——这类 ThreadLocal 泄漏在单请求测试里永远不报错，只在并发复用时串号（SC-007）。
 *
 * <p><b>只被 {@code AgentService} 放置与清除</b>，其他类只读。
 *
 * <p>放在 {@code core.engine} 而非 {@code core.profile}：它是<b>逐请求运行态</b>，生命周期归 {@code AgentService}；
 * {@code core.profile} 装的是 {@code Profile} / {@code ProfileLoader} / {@code ProfileRegistry}
 * 这组静态配置。
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  /**
   * 标记本次处理的当前 Agent。
   *
   * @param profile 本次会话所属的 Agent 配置
   */
  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /**
   * 读当前 Agent。
   *
   * @return 当前处理的 Agent 配置；未设置时返回 {@code null}（不是 {@code Optional}——课件 harness 用 {@code
   *     assertNull(ProfileContext.current())} 逐字钉死这一条）
   */
  public static Profile current() {
    return CURRENT.get();
  }

  /** 清掉当前 Agent。处理结束的<b>任何</b>路径都必须走到这里。 */
  public static void clear() {
    CURRENT.remove();
  }
}
