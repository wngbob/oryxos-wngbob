package io.oryxlabs.oryxos.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>临时</b>沙箱实现：{@link #enforce} 是空实现，任何动作都放行。
 *
 * <p><b>它的存在理由是解一个装配上的死结</b>：第 20 节的工具（{@code FileTools} / {@code ShellTools} / {@code HttpTools} /
 * {@code NotifyTools}）构造时都要一个 {@link Sandbox}，而真正的白名单实现 {@code WhitelistSandbox} 归第 24
 * 节。若本节不挂一档实现，工具就进不了容器——第 19 节「零装配」的根因正是这个（没有可注入的沙箱 Bean）。
 *
 * <p><b>第 20~23 节之间白名单校验不生效</b>：这一段窗口期里 {@code read_file} 能读到白名单外的路径、{@code shell}
 * 能跑白名单外的可执行文件。这不是「沙箱已就位」，MUST NOT 被这样表述（contracts/assembly.md §6.5）。
 *
 * <p><b>第 24 节 MUST 替换掉它</b>：装配处声明 {@code WhitelistSandbox} 后，本类的
 * {@code @ConditionalOnMissingBean(Sandbox.class)} 让位机制自动生效，工具代码零改动。四条缓解见 contracts/assembly.md §4：
 * ① 类名与 javadoc 明示临时（本文件）；② <b>构造函数里打一条 WARN</b>——放在构造函数而不是装配方法，是为了让<b>任何</b>构造路径
 * （含绕过装配的单测、将来的其他装配点）都会警告，且可用纯单测钉死；③ 第 24 节同类型 Bean 自动让位；④ {@code CLAUDE.md} + 验收报告双处留痕。
 *
 * <p><b>MUST NOT 用匿名类替代本类</b>：匿名类无法让位、无法断言、无法写 javadoc 说明临时性（contracts/assembly.md §6.4）。
 */
public class PermissiveSandbox implements Sandbox {

  private static final Logger log = LoggerFactory.getLogger(PermissiveSandbox.class);

  /** WARN 文案冻结在 contracts/assembly.md §4：必须同时含「临时」与「第 24 节」，让读日志的人不可能误判。 */
  private static final String WARN_MESSAGE =
      "沙箱当前挂的是临时实现（PermissiveSandbox）：白名单校验不生效。第 24 节落地 WhitelistSandbox 后自动替换。";

  /** 构造即警告——任何构造路径都留下痕迹，而不是只有装配路径。 */
  public PermissiveSandbox() {
    log.warn(WARN_MESSAGE);
  }

  /**
   * 放行一切动作。
   *
   * <p><b>刻意不写 {@code // TODO}</b>：这是已报批的取舍（spec 仲裁 1 / research D1），不是待办遗漏。TODO 会被后来者当成「忘了写」，
   * 而正确的心智模型是「第 24 节会整类替换掉它」。
   *
   * @param action 待校验动作；本实现不读它
   */
  @Override
  public void enforce(SandboxAction action) {
    // 临时实现：不做任何校验，直接返回
  }
}
