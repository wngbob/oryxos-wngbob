package io.oryxlabs.oryxos.tool.notify;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按 {@link NotifyTarget#channelType()} 把一次推送分派给对应档实现的适配器。
 *
 * <p><b>为什么现在才有它</b>：第二档实现（飞书 / Lark 方言）出现之前，{@code NotifyTools} 注入的就是唯一那个适配器，「按类型选实现」没有调用方，
 * 属于预造。现在有了第二个实现，这个调用方就出现了——不是提前抽象，是需求到了。
 *
 * <p><b>为什么是显式的 {@code Map} 而不是「遍历各实现问它支不支持」</b>：后者等价于按类型扫描，章程原则 10 明确禁止（与 {@code ProviderService}
 * 的 provider 映射表同源，那里的未命中同样抛明确异常、不做静默兜底）。映射表由<b>装配处</b>逐条给出，「哪个 type 对应哪个实现」是装配期事实，不是运行期猜测。
 *
 * <p><b>本类不含任何厂商字面量</b>：{@code feishu} / {@code lark} / {@code webhook} 这些取值与别名一律留在装配处。理由与 {@code
 * NotifyTools}「三个参数全是接口类型」一致——具体实现的知识只在装配点，本类因此对「将来加哪一家」完全无感。
 *
 * <p><b>未知 type 抛异常，MUST NOT 静默退回某一档</b>：静默退回意味着消息发到了没人预期的群，比报错严重得多（与渠道注册表对「名字打错」的立场一致）。
 * 这条也顺带修掉了一个真实缺陷——把 {@code type} 拼错时，旧口径是「原样交给通用档」，而通用档对 IM 机器人只看 HTTP 状态码，于是拼错 {@code type}
 * 会<b>静默假成功</b>。改后拼错在路由处就炸，一次请求都不发。
 *
 * <p><b>归 {@code oryxos-tool} 且不带 Spring 注解</b>：与各档实现同处一个模块，可脱离容器单测；装配只在 {@code oryxos-cli}。
 *
 * @see NotifyChannelAdapter
 */
public class RoutingNotifyChannelAdapter implements NotifyChannelAdapter {

  private final Map<String, NotifyChannelAdapter> adapterByType;

  /**
   * @param adapterByType 渠道类型 → 推送实现；<b>键必须是小写</b>（由装配处保证），值不得为 null
   */
  public RoutingNotifyChannelAdapter(Map<String, NotifyChannelAdapter> adapterByType) {
    this.adapterByType = Map.copyOf(adapterByType);
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String type = target.channelType();
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("通知渠道的 type 为空，无法确定用哪一档推送实现；已知类型：" + knownTypes());
    }
    // 归一化只发生在查找侧：type 是人手写进注册表的值，带空白或大小写不一是常态。
    // 用 Locale.ROOT 而不是默认 locale——土耳其语环境下 "I".toLowerCase() 会得到无点的 ı，匹配当场失效。
    NotifyChannelAdapter adapter = adapterByType.get(type.trim().toLowerCase(Locale.ROOT));
    if (adapter == null) {
      throw new IllegalArgumentException("未知的通知渠道类型 '" + type + "'；已知类型：" + knownTypes());
    }
    adapter.send(target, content);
  }

  /**
   * 已知类型的可读清单，用于报错。
   *
   * <p><b>必须排序</b>：{@code Map.copyOf} 的迭代顺序未指定，直接拼 {@code keySet()} 会让报错文案在每次运行间抖动，测试断言随之 flaky。
   *
   * @return 逗号分隔的已知类型
   */
  private String knownTypes() {
    return adapterByType.keySet().stream().sorted().collect(Collectors.joining(", "));
  }
}
