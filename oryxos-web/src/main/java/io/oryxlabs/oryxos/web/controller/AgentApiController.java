package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.InvokeRequest;
import io.oryxlabs.oryxos.web.controller.dto.InvokeResponse;
import io.oryxlabs.oryxos.web.error.ProfileNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 无状态调用端点（contracts/rest-endpoints.md §2）。
 *
 * <p><b>一次性语义</b>：不创建持久会话、不携带历史，两次调用互相看不见——实际处理在 {@code AgentService.processStateless}，与有状态入口共用同一个
 * ReAct 循环（FR-002）。
 *
 * <p><b>为什么 Agent 不存在要在本类先判</b>：{@code AgentService} 对未知 Agent 抛的是 {@code
 * IllegalStateException}（会落到 500），而契约要求 404——「你要的 Agent 没有」是资源不存在，不是底座故障。
 */
@RestController
@RequestMapping("/api/v1/agents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 26 节交付的对外入口本身（FR-001），不是缺陷")
public class AgentApiController {

  /** 单条消息上限 32KB（与 §1.2 同一口径，FR-008）。 */
  private static final int MAX_MESSAGE_LENGTH = 32768;

  private final AgentService agentService;
  private final ProfileRegistry profileRegistry;

  /**
   * @param agentService 唯一处理入口
   * @param profileRegistry 按名查 Agent 是否还在
   */
  public AgentApiController(AgentService agentService, ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.profileRegistry = profileRegistry;
  }

  /**
   * 一次性调用某个 Agent。
   *
   * @param name Agent 名
   * @param request 请求体
   * @return Agent 名与最终答复
   */
  @PostMapping("/{name}/invoke")
  public ApiResponse<InvokeResponse> invoke(
      @PathVariable String name, @RequestBody InvokeRequest request) {
    String content = request.content();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required");
    }
    if (content.length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("message exceeds 32KB limit");
    }
    if (profileRegistry.find(name).isEmpty()) {
      throw new ProfileNotFoundException(name);
    }
    return ApiResponse.ok(new InvokeResponse(name, agentService.processStateless(name, content)));
  }
}
