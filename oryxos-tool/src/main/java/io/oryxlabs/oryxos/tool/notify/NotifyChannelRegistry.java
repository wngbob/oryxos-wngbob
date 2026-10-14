package io.oryxlabs.oryxos.tool.notify;

/**
 * 渠道注册表接缝：按注册名解析出通知目标。
 *
 * <p><b>为什么通知工具自己持这个接缝，而不是问 Profile 要</b>：渠道是<b>全局</b>注册表，不属于任何 Agent， 解析也不需要知道「当前是哪个 Agent」。而课件的
 * {@code profileContext.resolveNotifyChannel(channel)} 落不了地—— {@link NotifyTarget} 契约在 {@code
 * oryxos-tool}、{@code ProfileContext} 在 {@code oryxos-core}，给 core 加这个方法 就是 core 反向依赖能力模块（违反依赖倒置）。
 *
 * <p><b>只有一个方法，且是只读的</b>：本节的交付范围是「表 + 存取 + 按名解析」，写入路径归第 26 节。 所以这里不出现 {@code save} / {@code
 * delete}，也不出现 {@code list} / {@code find} / {@code exists}—— 多一个方法就多一处「谁在什么时候写渠道」的模糊地带。
 *
 * <p><b>返回 {@link NotifyTarget} 而不是 {@code Optional} 或 {@code null}</b>：解析不到是<b>错误</b>，
 * 不是「没有」——Agent 不能以为消息已经发出去了（FR-006）。用异常表达让调用方无法忽略。
 */
public interface NotifyChannelRegistry {

  /**
   * 按注册名解析通知目标。
   *
   * @param channelName 渠道注册名；{@code null} / 空串 / 纯空白等同「不指定」，取默认渠道
   * @return 解析出的通知目标
   * @throws IllegalStateException 未配置任何渠道（且未指定名字），或指定了名字但注册表里没有它
   * @throws IllegalArgumentException 该行的 {@code type} 为空白，或 {@code config_json} 形状非法
   */
  NotifyTarget resolve(String channelName);
}
