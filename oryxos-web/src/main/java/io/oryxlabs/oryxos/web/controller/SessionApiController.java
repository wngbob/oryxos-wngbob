package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionMessage;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.ArchiveResponse;
import io.oryxlabs.oryxos.web.controller.dto.CreateSessionRequest;
import io.oryxlabs.oryxos.web.controller.dto.MessageRequest;
import io.oryxlabs.oryxos.web.controller.dto.MessageResponse;
import io.oryxlabs.oryxos.web.controller.dto.MessageView;
import io.oryxlabs.oryxos.web.controller.dto.SessionView;
import io.oryxlabs.oryxos.web.error.ProfileNotFoundException;
import io.oryxlabs.oryxos.web.error.SessionNotFoundException;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理四端点（contracts/rest-endpoints.md §1）。
 *
 * <p><b>本层只做三件事</b>：参数校验、响应包装、错误处理（FR-003）。发消息<b>汇入与 {@code oryxos chat} 完全相同的 {@code
 * AgentService.process}</b>——HTTP 层不另起处理链路（FR-002）。
 *
 * <p><b>会话标识不在本类拼接</b>：调用方只给 {@code (channel, userId, profile)} 三元组，标识由 {@code SessionService}
 * 内部唯一一处拼（H4 不变量 ④）。{@code channel} 这里定死 {@code "web"}，请求体不含该字段。
 *
 * <p><b>为什么发消息前要再查一次 Profile</b>：{@code AgentService} 查不到 Agent 时抛的是 {@code
 * IllegalStateException}（配置 / 数据错误，归 500），而契约 §1.2 要求这条给 404——「会话还在、但它所属的 Agent 已经没了」对调用方是「资源不存在」。
 * 预校验把这条语义在进入处理链路前就定下来。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 26 节交付的对外入口本身（FR-001），不是缺陷")
public class SessionApiController {

  /** 服务端定死的渠道字面量（契约 §1.1）。 */
  private static final String CHANNEL = "web";

  /** {@code userId} 缺省值（契约 §1.1）。 */
  private static final String DEFAULT_USER_ID = "default";

  /** 单条消息上限 32KB（FR-008，防呆不是治理）。 */
  private static final int MAX_MESSAGE_LENGTH = 32768;

  /** 会话历史最多返回最近 100 条（FR-009）。 */
  private static final int MAX_HISTORY = 100;

  private final SessionService sessionService;
  private final AgentService agentService;
  private final ProfileRegistry profileRegistry;

  /**
   * @param sessionService 会话持久化接缝
   * @param agentService 三种触发源共用的唯一处理入口
   * @param profileRegistry 按名查 Agent 是否还在
   */
  public SessionApiController(
      SessionService sessionService, AgentService agentService, ProfileRegistry profileRegistry) {
    this.sessionService = sessionService;
    this.agentService = agentService;
    this.profileRegistry = profileRegistry;
  }

  /**
   * 创建（或取回）会话。同一 {@code (profile, userId)} 重复创建返回同一个会话（契约 §1.1）。
   *
   * @param request 请求体
   * @return 会话元数据，{@code messages} 为 {@code null}
   */
  @PostMapping
  public ApiResponse<SessionView> create(@RequestBody CreateSessionRequest request) {
    if (request.profile() == null || request.profile().isBlank()) {
      throw new IllegalArgumentException("profile is required");
    }
    requireProfile(request.profile());
    String userId =
        request.userId() == null || request.userId().isBlank() ? DEFAULT_USER_ID : request.userId();
    Session session = sessionService.getOrCreate(CHANNEL, userId, request.profile());
    // 必须当场落库：getOrCreate 对新三元组只造对象、不写行，而本端点的语义是「会话已存在」——
    // 下一步 send 先用 get(id) 查，查不到就 404。不落库时「先建会话、再发消息」这条主流程必然断在第二步
    // （实测：POST /sessions 返回 200，紧跟着 POST /sessions/{id}/messages 回 404 session not found）。
    // 已存在的会话走更新路径，写回的是同一份内容，幂等。
    sessionService.save(session);
    return ApiResponse.ok(toView(session, null));
  }

  /**
   * 向已有会话发一条消息并同步拿回最终答复（契约 §1.2）。
   *
   * @param id 会话标识
   * @param request 请求体
   * @return 会话标识与 Agent 最终答复
   */
  @PostMapping("/{id}/messages")
  public ApiResponse<MessageResponse> send(
      @PathVariable String id, @RequestBody MessageRequest request) {
    String content = request.content();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content is required");
    }
    if (content.length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("message exceeds 32KB limit");
    }
    Session session = requireSession(id);
    // 归档后不静默复活：这条必须在进入处理链路之前判，否则模型已经跑完一轮才报错
    if (Session.STATUS_ARCHIVED.equals(session.status())) {
      throw new IllegalArgumentException("session is archived: " + id);
    }
    requireProfile(session.profileName());
    String reply = agentService.process(session, content);
    return ApiResponse.ok(new MessageResponse(id, reply));
  }

  /**
   * 查会话历史。归档会话照样查得到、看得见状态（契约 §1.3）。
   *
   * @param id 会话标识
   * @return 会话详情，{@code messages} 为最近 100 条、按时间正序
   */
  @GetMapping("/{id}")
  public ApiResponse<SessionView> get(@PathVariable String id) {
    Session session = requireSession(id);
    return ApiResponse.ok(toView(session, recentMessages(session)));
  }

  /**
   * 归档会话。幂等：重复 DELETE 返回首次那个 {@code archivedAt}（契约 §1.4 / FR-021）。
   *
   * @param id 会话标识
   * @return 归档结果
   */
  @DeleteMapping("/{id}")
  public ApiResponse<ArchiveResponse> archive(@PathVariable String id) {
    if (!sessionService.archive(id)) {
      throw new SessionNotFoundException(id);
    }
    // 回读一次拿到「事实上的」归档时刻：幂等的判据是库里那一列，不是本次请求的 now
    Session archived = requireSession(id);
    return ApiResponse.ok(new ArchiveResponse(id, archived.status(), archived.archivedAt()));
  }

  private Session requireSession(String id) {
    return sessionService.get(id).orElseThrow(() -> new SessionNotFoundException(id));
  }

  private void requireProfile(String profileName) {
    if (profileRegistry.find(profileName).isEmpty()) {
      throw new ProfileNotFoundException(profileName);
    }
  }

  private static List<MessageView> recentMessages(Session session) {
    List<SessionMessage> messages = session.messages();
    int from = Math.max(0, messages.size() - MAX_HISTORY);
    return messages.subList(from, messages.size()).stream()
        .map(SessionApiController::toMessageView)
        .toList();
  }

  private static MessageView toMessageView(SessionMessage message) {
    return new MessageView(
        message.role().name().toLowerCase(Locale.ROOT), message.content(), message.timestamp());
  }

  private static SessionView toView(Session session, List<MessageView> messages) {
    return new SessionView(
        session.id(),
        session.profileName(),
        session.channel(),
        session.userId(),
        session.status(),
        messages,
        session.createdAt(),
        session.lastActiveAt(),
        session.archivedAt());
  }
}
