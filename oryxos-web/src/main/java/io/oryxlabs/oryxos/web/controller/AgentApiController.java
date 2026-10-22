package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.agent.AgentLifecycleService;
import io.oryxlabs.oryxos.core.agent.AgentView;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionMessage;
import io.oryxlabs.oryxos.core.tool.ToolExecutionContext;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.CreateAgentRequest;
import io.oryxlabs.oryxos.web.controller.dto.GenerateFilesRequest;
import io.oryxlabs.oryxos.web.controller.dto.InvokeRequest;
import io.oryxlabs.oryxos.web.controller.dto.InvokeResponse;
import io.oryxlabs.oryxos.web.controller.dto.MemoryView;
import io.oryxlabs.oryxos.web.controller.dto.MessageRequest;
import io.oryxlabs.oryxos.web.controller.dto.MessageResponse;
import io.oryxlabs.oryxos.web.controller.dto.MessageView;
import io.oryxlabs.oryxos.web.controller.dto.SaveFilesRequest;
import io.oryxlabs.oryxos.web.controller.dto.SessionView;
import io.oryxlabs.oryxos.web.controller.dto.UpdateAgentRequest;
import io.oryxlabs.oryxos.web.error.ProfileNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 资源端点：增删改查 + 一次性调用（第 26 节交付 {@code invoke}，第 30 节补齐其余五个）。
 *
 * <p><b>本类只做三件事</b>：参数校验、响应包装、错误处理（第 26 节 FR-003）。编排全部在 {@link AgentLifecycleService}——
 * 控制器里多做一步，就等于给「API 建的 Agent」和「丢目录进来的 Agent」造出两套行为。
 *
 * <p><b>为什么 Agent 不存在要在本类先判</b>：{@code AgentService} / {@code AgentLifecycleService} 对未知 Agent 抛的是
 * {@code IllegalArgumentException} / {@code IllegalStateException}（前者 400、后者 500），而契约要求 404——「你要的
 * Agent 没有」是资源不存在，不是参数写错、也不是底座故障。判据用 {@code ProfileRegistry.find}（唯一真相源的运行时投影）。
 *
 * <p><b>被拒请求零模型调用</b>（FR-022）：校验与冲突判定都在 {@code create} / {@code update} 之前完成，模型调用只可能发生在 {@code
 * invoke} 这条既有路径上。
 */
@RestController
@RequestMapping("/api/v1/agents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是对外入口本身（第 26 节 FR-001 / 第 30 节 FR-001），不是缺陷")
public class AgentApiController {

  /** 单条消息上限 32KB（与 §1.2 同一口径，FR-008）。 */
  private static final int MAX_MESSAGE_LENGTH = 32768;

  /** 会话历史返回上限：与 {@code SessionApiController} 同一口径（第 26 节契约的 100 条）。 */
  private static final int MAX_HISTORY = 100;

  private final AgentService agentService;
  private final ProfileRegistry profileRegistry;
  private final AgentLifecycleService lifecycleService;
  private final MemoryService memoryService;

  /**
   * @param agentService 唯一处理入口（{@code invoke} 用）
   * @param profileRegistry 按名查 Agent 是否还在
   * @param lifecycleService Agent 生命周期的唯一编排点
   * @param memoryService 记忆门面（Agent 专属记忆端点用）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "profileRegistry / lifecycleService 都是 Spring 注入的协作对象；前者自第 29 节起是运行时可变索引、"
              + "后者持有同一份索引（副本会让本类看不见运行时注册）；memoryService 同理，持有是依赖注入本身")
  public AgentApiController(
      AgentService agentService,
      ProfileRegistry profileRegistry,
      AgentLifecycleService lifecycleService,
      MemoryService memoryService) {
    this.agentService = agentService;
    this.profileRegistry = profileRegistry;
    this.lifecycleService = lifecycleService;
    this.memoryService = memoryService;
  }

  /**
   * 新建 Agent：模板脚手架 → 派生注册 → 有 {@code schedules} 则注册定时。
   *
   * @param request 名称 + 一句话说明
   * @return 新 Agent 的视图
   */
  @PostMapping
  public ApiResponse<AgentView> create(@RequestBody CreateAgentRequest request) {
    if (request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    return ApiResponse.ok(lifecycleService.create(request.name(), request.description()));
  }

  /**
   * 列出已注册的 Agent。
   *
   * @return 按名排序的视图列表
   */
  @GetMapping
  public ApiResponse<List<AgentView>> list() {
    return ApiResponse.ok(lifecycleService.list());
  }

  /**
   * 读一个 Agent 的详情。
   *
   * @param name Agent 名
   * @return 视图
   */
  @GetMapping("/{name}")
  public ApiResponse<AgentView> get(@PathVariable String name) {
    requireAgent(name);
    return ApiResponse.ok(lifecycleService.get(name));
  }

  /**
   * 覆写一个 Agent 的 {@code AGENT.md}，写完立即生效（{@code schedules} 变更先注销旧句柄）。
   *
   * @param name Agent 名
   * @param request 完整的 {@code AGENT.md}
   * @return 更新后的视图
   */
  @PutMapping("/{name}")
  public ApiResponse<AgentView> update(
      @PathVariable String name, @RequestBody UpdateAgentRequest request) {
    requireAgent(name);
    String markdown = request.agentMarkdown();
    if (markdown == null || markdown.isBlank()) {
      throw new IllegalArgumentException("agentMarkdown is required");
    }
    return ApiResponse.ok(lifecycleService.update(name, markdown));
  }

  /**
   * 删除一个 Agent：先停定时 → 移出索引 → 整目录归档（不物理删）。
   *
   * @param name Agent 名
   * @return 空数据的成功信封
   */
  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    requireAgent(name);
    lifecycleService.delete(name);
    return ApiResponse.ok(null);
  }

  /**
   * 按一句描述生成这个 Agent 的 {@code AGENT.md} 草稿：<b>只生成、不落盘、不注册</b>（前端先预览、可改后再保存）。
   *
   * @param name Agent 名
   * @param request 一句描述
   * @return 相对路径 → 内容
   */
  @PostMapping("/{name}/generate-files")
  public ApiResponse<Map<String, String>> generateFiles(
      @PathVariable String name, @RequestBody GenerateFilesRequest request) {
    requireAgent(name);
    String description = request.description();
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description is required");
    }
    return ApiResponse.ok(lifecycleService.generateFiles(name, description));
  }

  /**
   * 保存一组文件并让它立刻生效（{@code AGENT.md} 先校验，非法则一个文件都不写）。
   *
   * @param name Agent 名
   * @param request 相对路径 → 内容
   * @return 更新后的视图
   */
  @PostMapping("/{name}/files")
  public ApiResponse<AgentView> saveFiles(
      @PathVariable String name, @RequestBody SaveFilesRequest request) {
    requireAgent(name);
    if (request.files() == null || request.files().isEmpty()) {
      throw new IllegalArgumentException("files is required");
    }
    return ApiResponse.ok(lifecycleService.saveFiles(name, request.files()));
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
    requireAgent(name);
    return ApiResponse.ok(new InvokeResponse(name, agentService.processStateless(name, content)));
  }

  /**
   * 读这个 Agent 的专属长期记忆（第 30 节 FR-007）。
   *
   * <p><b>为什么上下文由本类置入</b>：{@code MemoryService.partition()} 是第 26 节留下的无参方法（它不知道自己该读谁），而记忆落点由 {@link
   * ToolExecutionContext} 决定。在这里 {@code callAs} 而不是改门面签名——门面签名一变，工具调用那条路径也得跟着改，而那条路径的上下文 由 {@code
   * ToolExecutor} 置入，本来就对。
   *
   * @param name Agent 名
   * @return 核心区全文 + 归档区内容 + 归档区是否被截断
   */
  @GetMapping("/{name}/memory")
  public ApiResponse<MemoryView> memory(@PathVariable String name) {
    requireAgent(name);
    MemoryPartition partition = ToolExecutionContext.callAs(name, memoryService::partition);
    return ApiResponse.ok(
        new MemoryView(partition.core(), partition.archival(), partition.truncated()));
  }

  /**
   * 读这个 Agent 的管理台固定会话（{@code admin:console:<name>}），没有则建一条空的。
   *
   * @param name Agent 名
   * @return 会话详情，{@code messages} 为最近 100 条、按时间正序
   */
  @GetMapping("/{name}/session")
  public ApiResponse<SessionView> fixedSession(@PathVariable String name) {
    requireAgent(name);
    return ApiResponse.ok(toView(lifecycleService.fixedSession(name)));
  }

  /**
   * 往固定会话发一条消息并触发 ReAct（与 {@code invoke} 同一入口，区别只在于上下文会累积）。
   *
   * @param name Agent 名
   * @param request 消息正文
   * @return 会话标识与最终答复
   */
  @PostMapping("/{name}/session/messages")
  public ApiResponse<MessageResponse> sendMessage(
      @PathVariable String name, @RequestBody MessageRequest request) {
    String content = request.content();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required");
    }
    if (content.length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("message exceeds 32KB limit");
    }
    requireAgent(name);
    String reply = lifecycleService.sendToFixedSession(name, content);
    // 发完再取一次拿会话标识：三元组的唯一真相源在生命周期服务里，这里 MUST NOT 自己拼 "admin:console:" + name
    return ApiResponse.ok(new MessageResponse(lifecycleService.fixedSession(name).id(), reply));
  }

  /** 不存在 → 404（`ProfileNotFoundException` 由统一异常出口映射）。 */
  private void requireAgent(String name) {
    if (profileRegistry.find(name).isEmpty()) {
      throw new ProfileNotFoundException(name);
    }
  }

  /**
   * 会话 → 视图。
   *
   * <p><b>与 {@code SessionApiController} 的同名私有方法刻意重复、不抽公共类</b>：抽出来要么落在 {@code dto} 包里给 DTO 添静态工厂、要么
   * 新造一个映射器类型，而本仓「第三次出现才抽象」——这是第二次。两处口径（最近 100 条、正序）由各自的常量钉着，谁改了都只影响自己那组端点。
   */
  private static SessionView toView(Session session) {
    List<SessionMessage> messages = session.messages();
    int from = Math.max(0, messages.size() - MAX_HISTORY);
    List<MessageView> views =
        messages.subList(from, messages.size()).stream()
            .map(AgentApiController::toMessageView)
            .toList();
    return new SessionView(
        session.id(),
        session.profileName(),
        session.channel(),
        session.userId(),
        session.status(),
        views,
        session.createdAt(),
        session.lastActiveAt(),
        session.archivedAt());
  }

  private static MessageView toMessageView(SessionMessage message) {
    return new MessageView(
        message.role().name().toLowerCase(Locale.ROOT), message.content(), message.timestamp());
  }
}
