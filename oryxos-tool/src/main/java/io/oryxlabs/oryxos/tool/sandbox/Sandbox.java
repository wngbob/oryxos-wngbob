package io.oryxlabs.oryxos.tool.sandbox;

/**
 * 沙箱契约（技术方案 6.7，接口先行 / 决策六）。归 {@code oryxos-tool}。
 *
 * <p>只有一个方法，表达"在受控环境里执行一个动作"这个意图。核心阶段唯一实现是 {@code WhitelistSandbox}（应用层 Path/Pattern 白名单）；扩展阶段按容器 →
 * microVM 演进， 只新增实现类，不改接口和调用方。
 *
 * <p><b>不使用 Java {@code SecurityManager}</b>：它在 JDK 17 起已废弃、JDK 21 已不可用， 与本项目 JDK 21+ 要求冲突。
 *
 * <p>{@code FileTools} / {@code ShellTools} / {@code HttpTools} 在各自 {@code execute} 方法 开头调用 {@link
 * #enforce}，校验通过才执行真正的 IO。
 *
 * <p><b>骨架状态</b>：技术方案未写明 {@code enforce} 的返回类型；按"校验通过则正常返回、 失败则抛异常"的语义取 {@code void}。实现类随 US-2 /
 * US-4 落地。
 */
public interface Sandbox {

  /**
   * 校验一个动作是否被允许。
   *
   * @param action 待校验动作
   * @throws SandboxViolationException 校验失败
   */
  void enforce(SandboxAction action);
}
