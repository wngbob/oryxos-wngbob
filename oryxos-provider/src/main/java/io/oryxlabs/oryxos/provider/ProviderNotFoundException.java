package io.oryxlabs.oryxos.provider;

/**
 * Profile 点名的厂商在全局层清单（{@code oryxos.providers}）里找不到同名项。
 *
 * <p>为什么必须显式报错而不是静默换一家或留空跑过（FR-004、SC-002）：企业里多个 Agent 用不同厂商，路由错了会拿 A 家的 数据去问 B
 * 家的模型，是数据出境级别的错误；静默兜底让这种错误在系统里查不出来。
 *
 * <p>消息 MUST 含 Profile 点名的<b>原始厂商名</b>——大小写与空格按原样回显，不做大小写归一化等静默容错， 否则配置里写错的 {@code DeepSeek}
 * 会被"好心地"当成 {@code deepseek} 跑过去。
 *
 * <p>继承 {@link RuntimeException}：调用方（第 17 节的 ReAct 循环）不被迫 try-catch，由上层统一处理。
 */
public class ProviderNotFoundException extends RuntimeException {

  /**
   * @param providerName Profile 点名的原始厂商名（可为 {@code null}，表示 Profile 根本没声明厂商）
   */
  public ProviderNotFoundException(String providerName) {
    super(
        "未声明的 provider: '"
            + providerName
            + "'——请检查 AGENT.md 的 provider.name 与 application.yaml 的 oryxos.providers 是否对得上");
  }
}
