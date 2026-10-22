package io.oryxlabs.oryxos.core.agent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Agent 生命周期的<b>唯一编排点</b>（第 30 节 FR-001 / FR-002）。
 *
 * <p><b>磁盘是真相源，本类只负责「写完磁盘之后让谁生效」</b>：建 / 改 / 删目录由 {@link AgentStore} 做，派生与校验由 {@link ProfileLoader}
 * 做，索引与定时注册由 {@link ProfileRegistry} / {@link AgentScheduler} 做——本类把这几步按正确顺序串起来，
 * 并在失败时把已经落下的东西收回去。把编排写在控制器里，会得到「API 建的文件能注册、丢目录的文件不能」两套行为。
 *
 * <p><b>两条录入路径一段代码</b>（FR-002）：API 的 {@link #create(String, String)} 在写完目录后调用 {@link
 * #register(Path)}， 实时监听器的 {@code WorkspaceWatcher}
 * 拾取目录后调用的是<b>同一个方法</b>。这不是「行为一致」的口头承诺，而是同一个方法签名—— {@code
 * AgentLifecycleServiceTest.create_andWatcherShareTheSameRegisterPath} 从 API 侧钉死，{@code
 * WorkspaceWatcherTest} 从目录侧钉死。
 *
 * <p><b>为什么校验全在写盘之前</b>：写坏了再回滚代码是可以写对的，但「回滚失败」这条分支会让磁盘上留下半个 Agent；而把 {@code parse} + 厂商 /
 * 工具校验全部前移，写盘之后的失败就只剩 IO 异常这一类无法预防的。创建路径例外——模板由本类自己生成，校验只能在写完目录之后做， 故那条路径保留回滚。
 */
public class AgentLifecycleService {

  /** Agent 名的合法形状：字母或数字开头，其后允许 {@code . _ -}，最长 64（data-model.md §1.1）。 */
  private static final Pattern AGENT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

  /** 管理台固定会话的渠道名（data-model.md §3.3）。 */
  public static final String ADMIN_CHANNEL = "admin";

  /** 管理台固定会话的用户标识（data-model.md §3.3）。 */
  public static final String ADMIN_USER = "console";

  /** Agent 目录里的定义文件（唯一真相源）。 */
  private static final String AGENT_FILE = "AGENT.md";

  /** Markdown 代码围栏。 */
  private static final String CODE_FENCE = "```";

  /** frontmatter 的定界行。 */
  private static final String FRONTMATTER_DELIMITER = "---";

  /** frontmatter 里顶层 provider 段的键行（不带缩进才是顶层键）。 */
  private static final String PROVIDER_KEY = "provider:";

  /** frontmatter 的子级缩进（YAML 两个空格）。 */
  private static final String PROVIDER_INDENT = "  ";

  /** 生成调用的审计前缀：{@code llm_calls.session_id} 以它开头，便于把这批调用与真实对话分开查。 */
  private static final String AUTHOR_SESSION_PREFIX = "agent-author:";

  /** 生成调用的调用方 Agent 名（只出现在 Profile 里，不落库、不进索引）。 */
  private static final String AUTHOR_AGENT_NAME = "agent-author";

  private final ProfileLoader profileLoader;
  private final ProfileRegistry profileRegistry;
  private final AgentScheduler agentScheduler;
  private final AgentStore agentStore;
  private final LlmClient llmClient;
  private final ToolCatalog toolCatalog;
  private final SessionService sessionService;
  private final AgentService agentService;

  /**
   * 本实例已配置的厂商名（{@code oryxos.providers[].name}）；core 不认 {@code SUPPORTED_PROVIDERS}，那在 provider 模块。
   */
  private final List<String> configuredProviders;

  /** 作者模型厂商；空串表示「取已配置清单第一个」。 */
  private final String authorProvider;

  /** 作者模型名；空串表示未配置（生成端点 503）。 */
  private final String authorModel;

  /**
   * @param profileLoader 目录 / 文本 → Profile（与启动扫描同一套校验）
   * @param profileRegistry 运行时可变的 Agent 索引
   * @param agentScheduler 定时任务注册 / 注销
   * @param agentStore 工作区磁盘读写（路径守卫也在它里面）
   * @param llmClient 「一句话生成」用的模型接缝
   * @param toolCatalog 校验 {@code tools} 是否已注册
   * @param sessionService 固定会话的取或建
   * @param agentService 固定会话的发消息入口（与 CLI / Web / 钟推同一条链路）
   * @param configuredProviders 本实例已配置的厂商名清单；可能为空
   * @param authorProvider 作者模型厂商；空串表示取清单第一个
   * @param authorModel 作者模型名；空串表示未配置
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "依赖注入本身：ProfileRegistry 自第 29 节起是运行时可变索引，本类必须持有同一个实例而不是副本"
              + "（副本会让本类刚注册的 Agent 在其它 Bean 那里看不见）。与 AgentService / PromptBuilder 同一口径")
  public AgentLifecycleService(
      ProfileLoader profileLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      LlmClient llmClient,
      ToolCatalog toolCatalog,
      SessionService sessionService,
      AgentService agentService,
      List<String> configuredProviders,
      String authorProvider,
      String authorModel) {
    this.profileLoader = Objects.requireNonNull(profileLoader, "profileLoader");
    this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry");
    this.agentScheduler = Objects.requireNonNull(agentScheduler, "agentScheduler");
    this.agentStore = Objects.requireNonNull(agentStore, "agentStore");
    this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    this.agentService = Objects.requireNonNull(agentService, "agentService");
    this.configuredProviders =
        List.copyOf(Objects.requireNonNull(configuredProviders, "configuredProviders"));
    this.authorProvider = authorProvider == null ? "" : authorProvider.strip();
    this.authorModel = authorModel == null ? "" : authorModel.strip();
  }

  /**
   * 创建一个 Agent：模板脚手架 → 派生 → 注册索引 → 有 {@code schedules} 则注册定时。
   *
   * <p><b>模板不调模型</b>：创建要可复现、要快、要能在没有作者模型的环境里用；「一句话生成」是另一条路径，产物先给人看再落盘。
   *
   * <p><b>写盘之后任何一步失败都回滚</b>：删掉刚建好的目录再原样上抛——「创建成功」的承诺是「它现在可用」，留下一个注册不上的目录
   * 比创建失败更难查（它会一直出现在文件浏览器里、却永远不出现在 Agent 列表里）。
   *
   * @param name Agent 名（= 目录名）
   * @param description 一句话说明，可空
   * @return 新 Agent 的视图
   * @throws IllegalArgumentException 名称非法 / 已存在 / 没有配置任何厂商 / 模板派生失败
   */
  public AgentView create(String name, String description) {
    requireValidName(name);
    if (profileRegistry.exists(name) || agentStore.exists(name)) {
      throw new IllegalArgumentException("Agent 已存在: '" + name + "'");
    }
    String provider = resolveAuthorProvider();
    String model = authorModel.isBlank() ? provider : authorModel;
    Path dir = agentStore.scaffold(name, description, provider, model);
    try {
      return AgentView.from(register(dir));
    } catch (RuntimeException e) {
      // 回滚：磁盘上不许留下「注册不上」的半个 Agent
      agentStore.delete(name);
      throw e;
    }
  }

  /**
   * 从一个<b>已存在的磁盘目录</b>注册 Agent（API 创建与实时监听器共用这一段，FR-002）。
   *
   * <p>派生走 {@link ProfileLoader#deriveProfile}——与启动扫描同一套校验、同一句失败消息；派生失败一律上抛，由调用方决定
   * 「创建失败要回滚」还是「监听到坏目录要 WARN 跳过」。
   *
   * @param agentDir Agent 目录（{@code .../agents/<name>}）
   * @return 派生出的 Profile
   * @throws io.oryxlabs.oryxos.core.profile.ProfileLoadException 目录里没有合法的 {@code AGENT.md}
   */
  public Profile register(Path agentDir) {
    Profile profile = profileLoader.deriveProfile(agentDir);
    activate(profile);
    return profile;
  }

  /**
   * 注销一个 Agent 的运行时痕迹：先停定时，再移出索引（目录本身不动）。
   *
   * <p><b>先停定时</b>：留着句柄就会有一个「索引里已经没有、却还在按时跑」的 Agent——它的消息进不来，但它会继续写审计、继续推消息。
   *
   * @param name Agent 名
   */
  public void unregister(String name) {
    profileRegistry.find(name).ifPresent(agentScheduler::unregisterProfile);
    profileRegistry.remove(name);
  }

  /**
   * @param name Agent 名
   * @return Agent 视图
   * @throws IllegalArgumentException 索引里没有这个 Agent
   */
  public AgentView get(String name) {
    return AgentView.from(requireAgent(name));
  }

  /**
   * @return 已注册的 Agent 视图（按名排序，顺序由 {@link ProfileRegistry#all()} 保证）
   */
  public List<AgentView> list() {
    return profileRegistry.all().stream().map(AgentView::from).toList();
  }

  /**
   * 用一个完整的 {@code AGENT.md} 覆写某个 Agent，并让它立刻生效。
   *
   * <p><b>校验全部前移</b>：只有 {@code parse} 与厂商 / 工具校验都通过才写盘——写坏一个正在运行的 Agent，等于把唯一真相源改坏。
   *
   * <p><b>先注销旧定时、再注册新的</b>：否则旧 cron 与新 cron 会并存，同一件事被跑两遍。归属判定按 Agent 名精确相等（第 29 节 {@code
   * unregisterProfile}），不是按任务 id 前缀。
   *
   * @param name Agent 名（目录名才是真相源，文本里的名字不参与）
   * @param agentMarkdown 完整的 {@code AGENT.md} 内容
   * @return 更新后的视图
   * @throws IllegalArgumentException Agent 不存在 / {@code AGENT.md} 非法 / 未知厂商 / 工具未注册
   */
  public AgentView update(String name, String agentMarkdown) {
    Profile previous = requireAgent(name);
    Profile updated = parseAndValidate(name, agentMarkdown);
    agentStore.writeAgentMarkdown(name, agentMarkdown);
    agentScheduler.unregisterProfile(previous);
    activate(updated);
    return AgentView.from(updated);
  }

  /**
   * 删除一个 Agent：注销定时 → 移出索引 → 整目录归档。
   *
   * <p><b>不物理删</b>：目录移进 {@code archive/}，重名时加时间戳后缀。Agent 的定义是一份人写的配置，误删的代价远高于占一点磁盘。
   *
   * <p><b>任何一步失败都不回滚前面的步骤</b>：已经停掉的定时不该因为归档失败又被恢复——那会让「删了一半」变成一个仍在按时运行的 Agent。
   *
   * @param name Agent 名
   * @throws IllegalArgumentException Agent 不存在
   */
  public void delete(String name) {
    Profile previous = requireAgent(name);
    agentScheduler.unregisterProfile(previous);
    profileRegistry.remove(name);
    agentStore.archive(name);
  }

  /**
   * 校验并派生存放在 {@code agentMarkdown} 里的定义，<b>不写盘</b>。
   *
   * @param name Agent 名
   * @param agentMarkdown 完整 {@code AGENT.md} 文本
   * @return 校验通过的 Profile
   * @throws IllegalArgumentException 解析失败 / 未知厂商 / 工具未注册
   */
  public Profile parseAndValidate(String name, String agentMarkdown) {
    Profile profile;
    try {
      profile = profileLoader.parse(agentMarkdown, name);
    } catch (RuntimeException e) {
      // ProfileLoadException 的构造器是包级私有，这里只转述原因（web 层统一映射成 400）
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    validateDefinition(profile);
    return profile;
  }

  /**
   * 每个 Agent 的固定管理台会话（data-model.md §3.3）：{@code channel=admin} / {@code user=console} / {@code
   * profile=<name>}。
   *
   * <p><b>幂等</b>：三元组固定，故同一条会话反复取回同一条 {@code admin:console:<name>}，上下文因此可以累积——这正是它与 {@code
   * invoke}（一次性、无历史）的区别。
   *
   * @param name Agent 名
   * @return 固定会话
   * @throws IllegalArgumentException Agent 不存在
   */
  public Session fixedSession(String name) {
    requireAgent(name);
    return sessionService.getOrCreate(ADMIN_CHANNEL, ADMIN_USER, name);
  }

  /**
   * 往固定会话里发一条消息并触发一轮 ReAct。
   *
   * <p><b>与 {@code invoke} 同一条入口</b>（{@code AgentService.process}）：差别只在会话来自 {@code sessions}
   * 表、带历史。持久化由 {@code process} 末尾的 {@code save} 完成，这里不再重复写一次。
   *
   * @param name Agent 名
   * @param content 用户输入
   * @return 模型的最终答复
   * @throws IllegalArgumentException Agent 不存在或会话已归档
   */
  public String sendToFixedSession(String name, String content) {
    Session session = fixedSession(name);
    if (Session.STATUS_ARCHIVED.equals(session.status())) {
      throw new IllegalArgumentException("会话已归档，不能再发消息: " + session.id());
    }
    return agentService.process(session, content);
  }

  /**
   * 按一句描述让作者模型生成一份 {@code AGENT.md} 草稿，<b>校验但不落盘、不注册</b>（FR-004）。
   *
   * <p><b>调用方一律用作者配置</b>，不跟随目标 Agent：目标 Agent 若是 {@code mock} 档，跟随它等于把生成请求打到固定脚本上， 生成出来的东西与被生成的
   * Agent 无关。
   *
   * <p><b>作者 model 留空直接拒绝</b>：往 OpenAI 兼容端点发 {@code model=null} 换来的是一句对端报错，运维要绕一大圈才知道是本机少配了一个键。
   *
   * <p><b>草稿的 provider 改写为目标 Agent 现有的那家</b>：模型不知道本仓配了哪些厂商，写错一家就会在保存后 400；而 「这个 Agent
   * 用哪家模型」本就是既有事实，让模型猜它没有任何收益。
   *
   * @param name Agent 名（必须是已存在的 Agent——生成的是它的文件草稿）
   * @param description 一句描述
   * @return 相对路径 → 内容（本节只有 {@code AGENT.md}）
   * @throws IllegalArgumentException Agent 不存在 / 草稿无法解析 / 草稿引用未知厂商或未注册工具
   * @throws AgentAuthorUnavailableException 作者模型未配置
   */
  public Map<String, String> generateFiles(String name, String description) {
    Profile existing = requireAgent(name);
    if (authorModel.isBlank()) {
      throw new AgentAuthorUnavailableException("未配置作者模型：请设置 oryxos.author.model");
    }
    String provider = resolveAuthorProvider();
    String sessionId = AUTHOR_SESSION_PREFIX + UUID.randomUUID();
    ChatResponse response =
        llmClient.chat(sessionId, authorProfile(provider), authorPrompt(description));
    String draft = alignProvider(stripCodeFences(textOf(response)), existing);
    validateGenerated(name, draft);
    return Map.of(AGENT_FILE, draft);
  }

  /**
   * 保存一组文件到某个 Agent 目录并让它立刻生效（FR-005）。
   *
   * <p><b>校验全部前移</b>：{@code AGENT.md} 不合法时一个文件都不写——写坏一个正在运行的 Agent 等于把唯一真相源改坏。
   *
   * <p><b>先注销旧定时、再注册新的</b>：与 {@link #update(String, String)} 同一条顺序理由，两者共用 {@link
   * #activate(Profile)}。
   *
   * @param name Agent 名
   * @param files 相对路径 → 内容；必须含 {@code AGENT.md}
   * @return 更新后的视图
   * @throws IllegalArgumentException Agent 不存在 / 缺 {@code AGENT.md} / 内容非法 / 路径越界
   */
  public AgentView saveFiles(String name, Map<String, String> files) {
    Profile previous = requireAgent(name);
    String agentMarkdown = files == null ? null : files.get(AGENT_FILE);
    if (agentMarkdown == null) {
      throw new IllegalArgumentException("文件清单必须包含 " + AGENT_FILE);
    }
    Profile updated = parseAndValidate(name, agentMarkdown);
    agentStore.writeAll(name, files);
    agentScheduler.unregisterProfile(previous);
    activate(updated);
    return AgentView.from(updated);
  }

  /** 索引注册 + 有 schedules 则注册定时：三条录入路径（create / update / saveFiles）共用的最后一步。 */
  private void activate(Profile profile) {
    profileRegistry.register(profile);
    if (!profile.schedules().isEmpty()) {
      agentScheduler.registerProfile(profile);
    }
  }

  /** 厂商 / 工具校验：provider 必须在<b>本实例</b>的清单里，tools 必须都已注册（FR-011）。 */
  private void validateDefinition(Profile profile) {
    Profile.ProviderSpec provider = profile.provider();
    String providerName = provider == null || provider.name() == null ? "" : provider.name();
    if (!configuredProviders.contains(providerName)) {
      throw new IllegalArgumentException(
          configuredProviders.isEmpty()
              ? "未知厂商: '" + providerName + "'；当前没有配置任何厂商（oryxos.providers 为空）"
              : "未知厂商: '" + providerName + "'；已配置: " + configuredProviders);
    }
    for (String tool : profile.tools()) {
      if (toolCatalog.find(tool).isEmpty()) {
        throw new IllegalArgumentException("工具未注册: '" + tool + "'");
      }
    }
  }

  /** 作者厂商：配置优先，其次已配置清单第一个；都没有时给出可读错误（MUST NOT 写出 provider 为空的模板）。 */
  private String resolveAuthorProvider() {
    if (!authorProvider.isBlank()) {
      if (!configuredProviders.contains(authorProvider)) {
        throw new IllegalArgumentException(
            "未知厂商: '" + authorProvider + "'（oryxos.author.provider）；已配置: " + configuredProviders);
      }
      return authorProvider;
    }
    if (configuredProviders.isEmpty()) {
      throw new IllegalArgumentException("没有配置任何厂商：请先在 oryxos.providers 声明至少一家（当前为空）");
    }
    return configuredProviders.get(0);
  }

  /** 生成用的调用方 Profile：它是「一次性的作者身份」，不进索引、不落盘，故只有 provider / model 有意义。 */
  private Profile authorProfile(String provider) {
    return new Profile(
        AUTHOR_AGENT_NAME,
        "按一句描述生成 AGENT.md",
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec(provider, authorModel, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  private static Prompt authorPrompt(String description) {
    return new Prompt(
        List.of(new SystemMessage(AgentAuthorPrompt.PROMPT), new UserMessage(description)));
  }

  private static String textOf(ChatResponse response) {
    Generation generation = response == null ? null : response.getResult();
    if (generation == null) {
      throw new IllegalArgumentException("作者模型没有返回任何内容");
    }
    String text = generation.getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("作者模型没有返回任何内容");
    }
    return text;
  }

  /** 提示词要求「不要用代码块围栏包裹」，但模型经常照旧包一层——剥掉比报错便宜。 */
  private static String stripCodeFences(String text) {
    String trimmed = text.strip();
    if (!trimmed.startsWith(CODE_FENCE)) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed;
    }
    String body = trimmed.substring(firstNewline + 1).stripTrailing();
    if (body.endsWith(CODE_FENCE)) {
      body = body.substring(0, body.length() - CODE_FENCE.length());
    }
    return body.strip();
  }

  /**
   * 把草稿 frontmatter 里的顶层 {@code provider:} 段改写为该 Agent 现有的厂商 / 模型。
   *
   * <p><b>没有 {@code provider:} 段时原样返回</b>：那种草稿该由 {@code parse} 报出「缺少 provider 段」——凭空补一段等于替模型
   * 圆谎，用户会在预览里看到一份与自己要求无关的配置。
   */
  private static String alignProvider(String draft, Profile existing) {
    Profile.ProviderSpec provider = existing.provider();
    if (provider == null || provider.name() == null || provider.name().isBlank()) {
      return draft;
    }
    String[] lines = draft.split("\\R", -1);
    int closing = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FRONTMATTER_DELIMITER.equals(lines[i].trim())) {
        closing = i;
        break;
      }
    }
    if (closing < 0) {
      return draft;
    }
    int providerLine = -1;
    for (int i = 1; i < closing; i++) {
      if (PROVIDER_KEY.equals(lines[i].stripTrailing())) {
        providerLine = i;
        break;
      }
    }
    if (providerLine < 0) {
      return draft;
    }
    int end = providerLine + 1;
    while (end < closing
        && (lines[end].isBlank() || Character.isWhitespace(lines[end].charAt(0)))) {
      end++;
    }
    List<String> block =
        List.of(
            PROVIDER_KEY,
            PROVIDER_INDENT + "name: " + provider.name(),
            PROVIDER_INDENT + "model: " + provider.model());
    List<String> out = new ArrayList<>(lines.length + block.size());
    out.addAll(Arrays.asList(lines).subList(0, providerLine));
    out.addAll(block);
    out.addAll(Arrays.asList(lines).subList(end, lines.length));
    return String.join("\n", out);
  }

  /** 生成草稿的校验：解析失败与「已知厂商 / 已注册工具」失败分开报，前者额外标明「这是模型的锅」。 */
  private void validateGenerated(String name, String draft) {
    Profile profile;
    try {
      profile = profileLoader.parse(draft, name);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("生成的 AGENT.md 无法解析: " + e.getMessage(), e);
    }
    validateDefinition(profile);
  }

  private Profile requireAgent(String name) {
    return profileRegistry
        .find(name)
        .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: '" + name + "'"));
  }

  /** 名称校验：目录名来自 URL 路径段，含 {@code /} 或 {@code ..} 时不允许拼出 {@code agents/} 之外的位置。 */
  private static void requireValidName(String name) {
    if (name == null || name.isBlank() || !AGENT_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Agent 名称非法: '" + name + "'（只允许字母、数字、. _ -，且以字母或数字开头，最长 64）");
    }
  }
}
