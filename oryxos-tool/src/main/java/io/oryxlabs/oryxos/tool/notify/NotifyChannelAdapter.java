package io.oryxlabs.oryxos.tool.notify;

/**
 * 出站通知渠道契约（技术方案 6.8）。归 {@code oryxos-tool}。
 *
 * <p>跟 {@code Sandbox} 同样的思路：先定一个不携带具体渠道细节的抽象接口，表达 "把一条内容送到某个通知目标"这个意图。
 *
 * <p>与入站 Channel 对称但不是同一个东西：{@code ChannelAdapter} 解决"什么触发 Agent 开始跑"， {@code NotifyChannelAdapter}
 * 解决"Agent 跑完把结果送到哪"——语义方向相反，所以分开建模。
 *
 * <p><b>骨架状态</b>：技术方案未写明 {@code send} 的返回类型，取 {@code void}。 核心阶段实现 {@code
 * WebhookNotifyAdapter}（HTTP 类），扩展阶段补 {@code EmailNotifyAdapter}（SMTP）。
 */
public interface NotifyChannelAdapter {

  /**
   * 把内容发送到指定通知目标。
   *
   * @param target 通知目标
   * @param content 待发送内容
   */
  void send(NotifyTarget target, String content);
}
