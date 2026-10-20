package io.oryxlabs.oryxos.provider;

import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Provider 模块的核心入口：按 Profile 点名的厂商选模型、发起一次调用、把结果原样返回（技术方案 3.1）。
 *
 * <p>三条行为约定（契约 {@code contracts/provider-service-api.md} §1）：
 *
 * <ol>
 *   <li><b>按名路由</b>：用 Profile 点名的厂商名查<b>显式映射表</b>取模型；取不到抛 {@link ProviderNotFoundException}， MUST
 *       NOT 静默换一家、MUST NOT 留空跑过。
 *   <li><b>工具只翻译不执行</b>：请求上显式关闭框架自带的自动工具执行，否则工具会被执行两次且绕过上层统一审计。
 *   <li><b>成败都审计</b>：成功写 {@code success=true}；失败<b>先</b>写 {@code success=false} 加原因、<b>再</b>上抛异常。
 * </ol>
 *
 * <p><b>同步阻塞</b>：不引入响应式编程、不返回异步句柄、不自建线程池（章程原则 I）。
 */
public class ProviderService implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

  /** 显式映射表,厂商名 → 模型;由 ProviderConfiguration 逐条构造后注入,禁止按类型扫描容器 Bean。 */
  private final Map<String, ChatModel> providerMap;

  /**
   * 工具说明翻译器。
   *
   * <p><b>本节 chat 不消费本协作件</b>：按裁决，工具说明由调用方随 {@link Prompt} 的 options 携带，本服务只负责
   * 原样转发并强制关闭自动执行。保留此字段是为了与课件骨架的构造签名同构，且它确实是本模块对外的翻译能力入口。 第 17 节接入后若确认无用，再连同签名一起移除。
   */
  private final ToolSchemaAdapter adapter;

  /** 审计落库出口。 */
  private final LlmCallRecorder audit;

  /**
   * @param providerMap 显式映射表,厂商名 → 模型
   * @param adapter 工具说明翻译器
   * @param audit 审计落库出口
   */
  public ProviderService(
      Map<String, ChatModel> providerMap, ToolSchemaAdapter adapter, LlmCallRecorder audit) {
    this.providerMap = Map.copyOf(providerMap);
    this.adapter = adapter;
    this.audit = audit;
  }

  /**
   * 按 Profile 发起一次 LLM 调用。
   *
   * @param sessionId 会话标识；审计要落到 {@code llm_calls} 并按 session 关联，签名不带它就写不了审计
   * @param profile 决定用哪家厂商、哪个模型、什么温度
   * @param prompt 要发给模型的内容；其 options 里携带的工具说明会被原样转发
   * @return 模型响应，原样返回——响应里可能带着模型"想调某个工具"的意图，但执行不在本模块
   * @throws ProviderNotFoundException Profile 点名的厂商不在显式映射表里
   */
  @Override
  public ChatResponse chat(String sessionId, Profile profile, Prompt prompt) {
    Profile.ProviderSpec spec = profile.provider();
    String providerName = spec == null ? null : spec.name();
    ChatModel model = providerName == null ? null : providerMap.get(providerName);
    if (model == null) {
      throw new ProviderNotFoundException(providerName);
    }
    String modelName = spec.model();
    long startedAt = System.currentTimeMillis();
    try {
      ChatResponse response = model.call(request(prompt, spec));
      long durationMs = System.currentTimeMillis() - startedAt;
      Usage usage = response.getMetadata().getUsage();
      audit.record(sessionId, providerName, modelName, usage, true, null, durationMs);
      log.atInfo()
          .addKeyValue("event", "llm_call")
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("provider", providerName)
          .addKeyValue("model", modelName)
          .addKeyValue("success", true)
          .addKeyValue("durationMs", durationMs)
          .log("LLM 调用完成");
      return response;
    } catch (RuntimeException e) {
      // 先落账再上抛：只记成功不记失败，等于一次真实事故在系统里完全没留痕迹（章程原则 VI）
      long durationMs = System.currentTimeMillis() - startedAt;
      audit.record(sessionId, providerName, modelName, null, false, e.getMessage(), durationMs);
      log.atWarn()
          .addKeyValue("event", "llm_call")
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("provider", providerName)
          .addKeyValue("model", modelName)
          .addKeyValue("success", false)
          .addKeyValue("durationMs", durationMs)
          .addKeyValue("errorMessage", e.getMessage())
          .setCause(e)
          .log("LLM 调用失败");
      // 第 26 节：包装成「Provider 不可用」，让统一异常出口能映射 503；审计那一步在上一行已经做完，
      // 包装不改变「先落账再上抛」的顺序。cause 的 message 保留在异常消息里（排障用），HTTP 响应体不用它。
      throw new ProviderUnavailableException(providerName, e);
    }
  }

  /**
   * 组装真正发给模型的请求：用 Profile 的模型与温度覆盖，工具说明原样带上，并显式关闭自动执行。
   *
   * <p>关闭自动执行是"坑二"的解法（research.md D4）：开启时框架会在 {@code call} 内部替我们执行工具， 工具被调两次、且绕过了 {@code
   * ToolExecutor} 的统一执行与审计路径。
   */
  private static Prompt request(Prompt prompt, Profile.ProviderSpec spec) {
    DefaultToolCallingChatOptions.Builder options = DefaultToolCallingChatOptions.builder();
    options.model(spec.model());
    if (spec.temperature() != null) {
      options.temperature(spec.temperature());
    }
    if (prompt.getOptions() instanceof ToolCallingChatOptions incoming) {
      options.toolCallbacks(incoming.getToolCallbacks());
    }
    options.internalToolExecutionEnabled(false);
    return new Prompt(prompt.getInstructions(), options.build());
  }
}
