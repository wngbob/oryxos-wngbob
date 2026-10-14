package io.oryxlabs.oryxos.tool.notify;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 内置通知工具：模型只说「推什么」和「推到哪个渠道名」，剩下的由底座完成。
 *
 * <p><b>三个构造参数全部是接口类型</b>：{@link Sandbox} / {@link NotifyChannelAdapter} / {@link
 * NotifyChannelRegistry}。 出现任何具体实现名，这个类就绑死在某一档实现上，「换渠道只加实现类」这条承诺当场作废。
 *
 * <p><b>渠道解析由工具自己做，不问 Profile 要</b>：渠道是全局注册表，不属于任何 Agent。课件的 {@code
 * profileContext.resolveNotifyChannel(channel)} 落不了地——{@link NotifyTarget} 在 {@code oryxos-tool}、
 * {@code ProfileContext} 在 {@code oryxos-core}，给 core 加这个方法就是 core 反向依赖能力模块。
 *
 * <p><b>执行顺序不可颠倒：解析 → 白名单 → 发送</b>。通知是「往外推」的 HTTP 请求，不能因为方向与入站相反就绕过白名单；
 * 顺序写反（先发后校验）在功能上完全看不出区别，却把安全不变量变成了一句空话，故用 {@code InOrder} 断言钉死。
 *
 * <p><b>失败一律上抛，不自己 catch 后返回失败结果</b>：异常交给 {@code ToolExecutor}，成功失败都走同一条审计路径 （FR-008）。在这里 catch
 * 会让通知的失败在审计表里消失。
 *
 * <p><b>输出里不含渠道地址</b>：{@code url} 含 token，而工具结果是模型可见的对话内容（FR-019）。
 *
 * <p><b>本节零装配</b>：不带任何 Spring 注解。{@code @Tool} 只是 schema 元数据，注册机制归第 20 节；Bean 声明归第 24 节 （届时才有可注入的
 * {@link Sandbox} 实现）。
 */
public class NotifyTools {

  private final Sandbox sandbox;

  private final NotifyChannelAdapter adapter;

  private final NotifyChannelRegistry registry;

  public NotifyTools(
      Sandbox sandbox, NotifyChannelAdapter adapter, NotifyChannelRegistry registry) {
    this.sandbox = sandbox;
    this.adapter = adapter;
    this.registry = registry;
  }

  /**
   * 把一条消息推送到已注册的通知渠道。
   *
   * <p><b>方法名与参数名与课件逐字一致</b>：参数名会进 {@code @Tool} 生成的 JSON Schema，是模型看得见的部分， 改名等于改模型的行为。
   *
   * @param content 要推送的正文
   * @param channel 渠道注册名；不传时用默认渠道
   * @return 推送结果
   */
  @Tool(description = "把一条消息推送到已注册的通知渠道（不传渠道名时用默认渠道）")
  public ToolResult notify(String content, String channel) {
    NotifyTarget target = registry.resolve(channel);
    // 顺序钉死：先过白名单，再发。校验不通过时一次请求都不发
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")));
    adapter.send(target, content);
    return ToolResult.success("已推送");
  }
}
