package io.oryxlabs.oryxos.tool.builtin;

import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录型沙箱替身：记下每一次 {@code enforce} 调用，并可切换「放行」/「一律拒绝」（contracts/builtin-tools.md §3.3）。
 *
 * <p><b>为什么需要它，而不是直接注入一个真沙箱</b>：越界用例要断言的<b>不是「抛了异常」</b>，而是 「校验跑在 IO 之前」。先做 IO
 * 再校验同样会抛异常——那种实现下「抛异常」的断言照样绿，测试变成自证。本类额外记录 <b>{@code enforce}
 * 被调用的那一刻目标是否已存在</b>，写入类工具据此可以证明「文件是在校验通过之后才出现的」。
 *
 * <p><b>同时记录调用本身</b>：断言 {@code actions()} 里有且仅有预期的那个动作，排除「根本没调 {@code enforce}、 只是碰巧抛了别处的异常」这种假绿。
 *
 * <p>包级私有：只在 {@code io.oryxlabs.oryxos.tool.builtin} 的测试里用，不进任何对外概念清单。
 */
final class RecordingSandbox implements Sandbox {

  private final boolean allow;
  private final List<SandboxAction> actions = new ArrayList<>();
  private final List<Boolean> targetExistedAtEnforce = new ArrayList<>();

  /**
   * @param allow {@code true} = 全部放行（用于「正常能跑通」用例）；{@code false} = 一律抛 {@link
   *     SandboxViolationException}（用于「越界被拦」用例）
   */
  RecordingSandbox(boolean allow) {
    this.allow = allow;
  }

  @Override
  public void enforce(SandboxAction action) {
    actions.add(action);
    targetExistedAtEnforce.add(exists(action.target()));
    if (!allow) {
      throw new SandboxViolationException("越界: " + action.target());
    }
  }

  /** 按调用顺序记录的全部动作。 */
  List<SandboxAction> actions() {
    return List.copyOf(actions);
  }

  /**
   * 第 {@code index} 次 {@code enforce} 被调用的那一刻，它的目标是否已存在。
   *
   * <p>写入类工具的「校验先于 IO」就是靠这一项证明的：若写入发生在校验之前，这里会是 {@code true}。
   */
  boolean targetExistedWhenEnforced(int index) {
    return targetExistedAtEnforce.get(index);
  }

  /** 目标可能不是文件系统路径（HTTP 动作传的是 URL），那种情况按「不存在」处理，不抛异常。 */
  private static boolean exists(String target) {
    try {
      return Files.exists(Path.of(target));
    } catch (InvalidPathException e) {
      return false;
    }
  }
}
