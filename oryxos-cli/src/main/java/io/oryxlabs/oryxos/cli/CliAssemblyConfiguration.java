package io.oryxlabs.oryxos.cli;

import io.oryxlabs.oryxos.channel.cli.CliChannel;
import io.oryxlabs.oryxos.cli.workspace.AgentResolver;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.context.ContextLoader;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.engine.PromptBuilder;
import io.oryxlabs.oryxos.core.engine.ReActLoop;
import io.oryxlabs.oryxos.core.engine.ToolExecutor;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.memory.MemorySource;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.JpaSessionService;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.MemoryServiceImpl;
import io.oryxlabs.oryxos.memory.builtin.MemoryTools;
import io.oryxlabs.oryxos.memory.store.LongTermMemoryStore;
import io.oryxlabs.oryxos.memory.store.MarkdownMemoryStore;
import io.oryxlabs.oryxos.memory.store.Mem0MemoryStore;
import io.oryxlabs.oryxos.memory.store.SqliteMemoryStore;
import io.oryxlabs.oryxos.provider.JpaLlmCallRecorder;
import io.oryxlabs.oryxos.provider.LlmCallRecorder;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import io.oryxlabs.oryxos.provider.ProviderService;
import io.oryxlabs.oryxos.provider.ToolSchemaAdapter;
import io.oryxlabs.oryxos.storage.repository.LlmCallRepository;
import io.oryxlabs.oryxos.storage.repository.MemoryEntryRepository;
import io.oryxlabs.oryxos.storage.repository.NotifyChannelRepository;
import io.oryxlabs.oryxos.storage.repository.SessionRepository;
import io.oryxlabs.oryxos.storage.repository.ToolInvocationRepository;
import io.oryxlabs.oryxos.tool.builtin.FileTools;
import io.oryxlabs.oryxos.tool.builtin.HttpTools;
import io.oryxlabs.oryxos.tool.builtin.ShellTools;
import io.oryxlabs.oryxos.tool.interaction.ConsoleUserInteraction;
import io.oryxlabs.oryxos.tool.interaction.InteractionTools;
import io.oryxlabs.oryxos.tool.interaction.UserInteraction;
import io.oryxlabs.oryxos.tool.mcp.McpClientService;
import io.oryxlabs.oryxos.tool.mcp.McpServerConfigLoader;
import io.oryxlabs.oryxos.tool.notify.FeishuNotifyAdapter;
import io.oryxlabs.oryxos.tool.notify.JpaNotifyChannelRegistry;
import io.oryxlabs.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxlabs.oryxos.tool.notify.NotifyChannelRegistry;
import io.oryxlabs.oryxos.tool.notify.NotifyTools;
import io.oryxlabs.oryxos.tool.notify.RoutingNotifyChannelAdapter;
import io.oryxlabs.oryxos.tool.notify.WeComNotifyAdapter;
import io.oryxlabs.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxlabs.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxlabs.oryxos.tool.sandbox.Sandbox;
import io.oryxlabs.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxlabs.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxlabs.oryxos.tool.search.DuckDuckGoSearchProvider;
import io.oryxlabs.oryxos.tool.search.SearchProvider;
import io.oryxlabs.oryxos.tool.search.TavilySearchProvider;
import io.oryxlabs.oryxos.tool.search.WebSearchTools;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重命令的 Bean 装配——<b>全部 {@code @Bean} 的唯一落点</b>（contracts/cli-assembly.md §3 / §4 / §5）。
 *
 * <p><b>为什么集中在一处而不是给每个类加 {@code @Component}</b>：core 与能力模块的类型刻意不带 Spring 注解（它们是可单测的纯 Java 对象，第
 * 16/17 节的测试全部手工 {@code new}）。「谁被装配、装配成什么」只在这一处可见，第 20 / 22 / 26 节加 Bean 时改这一个类； 散到各模块就变成多处真相，且
 * core 会反向依赖 Spring 的装配语义。
 *
 * <p><b>15 个命令类 MUST NOT 自己 {@code new} 服务</b>：重命令从上下文取 Bean；轻命令走 {@code workspace/} 子包里的文件与 JDBC
 * 辅助类（它们根本不启动上下文）。
 */
@Configuration
@EnableConfigurationProperties({
  FileSandboxProperties.class,
  ShellSandboxProperties.class,
  HttpSandboxProperties.class
})
public class CliAssemblyConfiguration {

  /**
   * 工作区根路径。
   *
   * @return 进程当前目录下的 {@code .oryxos/}；<b>不检查是否存在</b>——命令进入前已用 {@code requireExists()} 拦过
   */
  @Bean
  public Workspace workspace() {
    return Workspace.current();
  }

  /**
   * Agent 解析闸门：{@code profile_name} 的存在性校验只此一处（spec 仲裁 11）。
   *
   * <p><b>已知厂商集合取 {@link ProviderProperties#SUPPORTED_PROVIDERS} 而不是实例配置里声明的清单</b>：轻命令读不到 {@code
   * oryxos.providers}（配置只在 {@code oryxos-boot} 的 classpath 上），按仲裁 6 只能取底座支持集合；重命令若改取 「配置里声明的清单」，同一个
   * Agent 就会出现「{@code profile list} 说有效、{@code chat} 说无效」的分歧。两处取同一个集合， 判定必然一致。配置声明了但代码没实现的厂商由
   * {@code ProviderService} 在调用时报错，那时报错同样清晰。
   *
   * @param workspace 工作区，取它的 {@code agents/} 目录
   * @return Agent 解析器
   */
  @Bean
  public AgentResolver agentResolver(Workspace workspace) {
    return new AgentResolver(workspace, ProviderProperties.SUPPORTED_PROVIDERS);
  }

  /**
   * 启动时装载一次 Agent 索引，之后只读。
   *
   * <p>重命令每次执行都新建上下文，因此这个「启动时快照」等价于「本条命令开始时的快照」——改完 {@code AGENT.md} 下一条命令即生效， 不需要重启任何常驻进程。
   *
   * @param workspace 工作区，取它的 {@code agents/} 目录
   * @return 按 Agent 名索引的 Profile 表
   */
  @Bean
  public ProfileRegistry profileRegistry(Workspace workspace) {
    ProfileLoader loader =
        new ProfileLoader(workspace.agentsDir(), ProviderProperties.SUPPORTED_PROVIDERS);
    return new ProfileRegistry(loader.loadAll());
  }

  /**
   * 会话持久化接缝的真实实现。
   *
   * @param sessionRepository {@code sessions} 表的存取出口
   * @return JPA 实现
   */
  @Bean
  public SessionService sessionService(SessionRepository sessionRepository) {
    return new JpaSessionService(sessionRepository);
  }

  /**
   * 每轮组装 prompt 时现读文件的那一层。
   *
   * @param workspace 工作区，取其 {@code .oryxos/} 目录
   * @return 文件加载器
   */
  @Bean
  public ContextLoader contextLoader(Workspace workspace) {
    return new ContextLoader(workspace.root());
  }

  /**
   * 组装 prompt 的部件。
   *
   * @param contextLoader 读 Bootstrap / Agent 正文 / Skill 绑定
   * @param memorySource 读长期记忆（本节为空接缝，第 22 节换真实实现）
   * @param toolCatalog 列本次可用工具（本节为空接缝，第 20 节换真实实现）
   * @return prompt 构造器
   */
  @Bean
  public PromptBuilder promptBuilder(
      ContextLoader contextLoader, MemorySource memorySource, ToolCatalog toolCatalog) {
    return new PromptBuilder(contextLoader, memorySource, toolCatalog);
  }

  /**
   * 工具调度器。
   *
   * @param toolCatalog 按名取工具（本节为空接缝，查不到任何工具）
   * @param toolInvocationRepository 工具调用的审计出口
   * @return 工具执行器
   */
  @Bean
  public ToolExecutor toolExecutor(
      ToolCatalog toolCatalog, ToolInvocationRepository toolInvocationRepository) {
    return new ToolExecutor(toolCatalog, toolInvocationRepository);
  }

  /**
   * ReAct 循环本体。
   *
   * @param promptBuilder 每轮组装发给模型的内容
   * @param llmClient 调一次模型的接缝；容器里只有 {@link #providerService} 一个实现，按类型即唯一命中
   * @param toolExecutor 工具调度（Spring AI 的自动工具执行已被关闭，调度只走这里，章程原则 III）
   * @return 循环
   */
  @Bean
  public ReActLoop reActLoop(
      PromptBuilder promptBuilder, LlmClient llmClient, ToolExecutor toolExecutor) {
    return new ReActLoop(promptBuilder, llmClient, toolExecutor);
  }

  /**
   * 三种触发源的唯一入口（章程原则 IX）。
   *
   * @param profileRegistry 按 Agent 名取 Profile
   * @param reActLoop 循环本体
   * @param sessionService 处理完持久化会话
   * @return 引擎
   */
  @Bean
  public AgentService agentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionService sessionService) {
    return new AgentService(profileRegistry, reActLoop, sessionService);
  }

  /**
   * 入站渠道适配器：{@code channel = cli}。{@code chat} 经它调 {@link AgentService#process}，自己不碰模型调用与工具调度。
   *
   * @param agentService 引擎
   * @param sessionService 取会话（三元组交出去，标识由它内部唯一一处拼）
   * @return CLI 渠道
   */
  @Bean
  public CliChannel cliChannel(AgentService agentService, SessionService sessionService) {
    return new CliChannel(agentService, sessionService);
  }

  /**
   * 工具说明翻译器。
   *
   * @return 只翻译不执行的适配器
   */
  @Bean
  public ToolSchemaAdapter toolSchemaAdapter() {
    return new ToolSchemaAdapter();
  }

  /**
   * LLM 调用的审计出口。
   *
   * @param llmCallRepository {@code llm_calls} 表的存取出口
   * @return 落库实现
   */
  @Bean
  public LlmCallRecorder llmCallRecorder(LlmCallRepository llmCallRepository) {
    return new JpaLlmCallRecorder(llmCallRepository);
  }

  /**
   * 模型调用接缝的<b>唯一</b>实现（contracts/cli-assembly.md §4：第 17 节刻意留白的接线在此接上）。
   *
   * <p><b>为什么 {@code @Qualifier("providerMap")} 不能省</b>：{@code Map<String, ChatModel>} 这个返回类型同时是
   * Spring「按类型收集容器里全部 Bean 成 Map」的语法，不指名就会注入一张按类型收集的空表，而不是 {@code ProviderConfiguration}
   * 逐条构造出来的显式映射表（章程原则 X 的反面教材）。
   *
   * <p><b>只声明这一个 Bean</b>：本类<b>不再</b>声明一个返回 {@code LlmClient} 的 {@code @Bean} 方法——那会让 {@code
   * LlmClient} 类型出现两个候选，注入直接歧义。{@code ProviderService} 实现 {@code LlmClient}，按类型即唯一命中。
   *
   * @param providerMap 厂商名 → 模型 的显式映射表
   * @param toolSchemaAdapter 工具说明翻译器
   * @param llmCallRecorder 调用审计出口
   * @return Provider 服务
   */
  @Bean
  public ProviderService providerService(
      @Qualifier("providerMap") Map<String, ChatModel> providerMap,
      ToolSchemaAdapter toolSchemaAdapter,
      LlmCallRecorder llmCallRecorder) {
    return new ProviderService(providerMap, toolSchemaAdapter, llmCallRecorder);
  }

  /**
   * 沙箱接缝的实现（技术方案 §6.7；contracts/sandbox-whitelist.md §4.1）。
   *
   * <p><b>返回类型取接缝类型 {@code Sandbox}，不取具体类</b>：调用方（{@code FileTools} / {@code ShellTools} / {@code
   * HttpTools} / {@code NotifyTools} / {@code WebSearchTools}）只认接口，扩展阶段换成容器 / microVM 档时本方法体改一行、
   * 五个工具一行不动。
   *
   * <p><b>保留 {@code @ConditionalOnMissingBean} 是有意留的覆盖接缝</b>：部署方自建一个 {@code Sandbox} Bean（例如未来的容器档）
   * 即接管，无需改 OryxOS 的代码。这不是「临时实现让位」——第 20~23 节那个不校验任何东西的临时实现已按裁决 3 <b>整类删除</b>： 它是个 fail-open
   * 组件，留在树里，Bean 声明一旦被误改（顺序 / 条件注解 / 被别处覆盖）就会静默回到「全放行」，兜底的只有一条 WARN 日志。 （历史留痕见 {@code
   * specs/005-tool-registry/} 与本节验收报告，不在这里复述类名。）
   *
   * <p><b>MUST NOT 用匿名类或 {@code @Primary} 代替</b>：匿名类无法让位、无法断言；{@code @Primary} 会让部署方的实现被永久遮蔽
   * （contracts/assembly.md §6.2 / §6.4）。
   *
   * <p><b>声明位置不影响任何让位链</b>：本方法不参与 {@code @ConditionalOnMissingBean} 的求值链（没有任何 Bean 依赖它的存在与否），
   * 故无需为它引入声明顺序约束。
   *
   * @param fileProps 文件路径白名单（{@code file.allowed_paths}）
   * @param shellProps 可执行文件名白名单（{@code shell.allowed_commands}）
   * @param httpProps HTTP 域名白名单（{@code http.allowed_domains}）
   * @return 应用层白名单沙箱
   */
  @Bean
  @ConditionalOnMissingBean(Sandbox.class)
  public Sandbox sandbox(
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    return new WhitelistSandbox(fileProps, shellProps, httpProps);
  }

  /**
   * 通知渠道的推送实现（第 19 节交付物，本节完成装配；2026-10-15 补强改为按 type 分派）。
   *
   * <p><b>「type → 实现」的映射表刻意写在这里</b>：{@link RoutingNotifyChannelAdapter} 保持厂商无关（源码里不出现任何一家渠道的名字），
   * 具体实现的知识只落在装配点——与 {@link NotifyTools}「三个参数全是接口类型」同一条纪律。
   *
   * <p><b>2026-10-15 修订（补强③）</b>：原文写「将来加第三家（企微 / 钉钉）只动这个方法」——<b>企微已按此路径加上</b>，改动确实只发生在本方法 （加一行
   * {@code put} 与一个 import）。钉钉仍待。原文保留以留痕。
   *
   * <p><b>{@link FeishuNotifyAdapter} 与 {@link WeComNotifyAdapter} 刻意不单独声明 Bean</b>：它们和通用档都实现
   * {@link NotifyChannelAdapter}，两个以上 Bean 会让 {@link NotifyTools} 的注入当场歧义。方言档在方法体内 new
   * 出来交给路由持有——容器里 {@code NotifyChannelAdapter} 类型的 Bean 恒为一个（由 {@code NotifyAssemblyTest} 起真上下文钉死）。
   *
   * <p><b>别名 {@code lark} 指向同一个实例</b>：Lark 是飞书的国际版，两者 webhook 协议与报文完全一致，各写一个类只会得到两份要同步维护的方言。
   * 企微<b>没有</b>这样的别名——它和飞书的报文形状只差一个字段名（{@code msgtype} vs {@code msg_type}），是镜像而不是同一份。
   *
   * <p><b>刻意不声明 {@code RestClient} Bean</b>：两档实现的无参构造内部各自自建带 10 秒有界超时的 {@code RestClient}。声明一个
   * {@code RestClient} Bean 会让人以为它可被注入替换，而第 19 节的 javadoc 明确把超时收口在类内（FR-021）。
   *
   * <p><b>{@code Map} 必须在本方法体内显式构造</b>：写成 {@code @Bean} 方法的 {@code Map<String,
   * NotifyChannelAdapter>} 参数， Spring 会按类型收集容器里所有候选 Bean——那正是 {@code providerMap} 需要
   * {@code @Qualifier} 的那个坑。
   *
   * @return 按渠道类型分派的推送器
   */
  @Bean
  public NotifyChannelAdapter notifyChannelAdapter() {
    Map<String, NotifyChannelAdapter> adapterByType = new LinkedHashMap<>();
    adapterByType.put("webhook", new WebhookNotifyAdapter());
    // 同一个实例挂两个键：飞书与 Lark 是同一套协议
    NotifyChannelAdapter feishu = new FeishuNotifyAdapter();
    adapterByType.put("feishu", feishu);
    adapterByType.put("lark", feishu);
    // 企微是独立的方言档，不与飞书共用实例：两者报文形状只差一个字段名，是镜像不是同一份
    adapterByType.put("wecom", new WeComNotifyAdapter());
    return new RoutingNotifyChannelAdapter(adapterByType);
  }

  /**
   * 通知渠道注册表（第 19 节交付物，本节完成装配）。
   *
   * <p><b>本节只读</b>：这个 Bean 只解析渠道，不写渠道。渠道的写入面是 {@code oryxos notify-channel add|remove}—— 三条**轻命令**，
   * 走原生 JDBC 直写表（不启 Spring，因此看不到本上下文、也拿不到这个 Bean）。
   *
   * <p><b>2026-10-15 修订（补强②）</b>：原文写「写入路径归第 26 节，故 {@code notify_channels} 表在运行期恒为空， {@code notify}
   * 工具此刻必然报『未配置任何通知渠道』——这是预期状态，不是缺陷」。前半句的挂账是错的（第 26 节的管理平台明确「这一版只读」），
   * 后半句的后果也<b>不再成立</b>：表不再恒为空，配好渠道后 {@code notify} 真能推出去。原文把「能力 100% 不可用」 记成「预期状态」，是这次补强的直接起因。
   *
   * @param notifyChannelRepository {@code notify_channels} 表的存取出口
   * @return 从表读渠道的注册表
   */
  @Bean
  public NotifyChannelRegistry notifyChannelRegistry(
      NotifyChannelRepository notifyChannelRepository) {
    return new JpaNotifyChannelRegistry(notifyChannelRepository);
  }

  /**
   * 通知工具（第 19 节交付物，本节完成装配——第 19 节因无可注入的 {@code Sandbox} 实现而挂账，本节挂上临时沙箱后解开）。
   *
   * <p><b>三个参数全是接口类型</b>：换一档推送实现或注册表实现，本方法与 {@link NotifyTools} 一行不改。
   *
   * @param sandbox 执行前的安全校验位
   * @param adapter 实际推送实现
   * @param registry 渠道名 → 推送目标
   * @return 通知工具 Bean
   */
  @Bean
  public NotifyTools notifyTools(
      Sandbox sandbox, NotifyChannelAdapter adapter, NotifyChannelRegistry registry) {
    return new NotifyTools(sandbox, adapter, registry);
  }

  /**
   * 文件工具（contracts/assembly.md §1.1 第 5 行）。
   *
   * @param sandbox 每个 {@code @Tool} 方法首行的安全校验位
   * @return {@code read_file} / {@code write_file} / {@code list_dir} 的宿主
   */
  @Bean
  public FileTools fileTools(Sandbox sandbox) {
    return new FileTools(sandbox);
  }

  /**
   * 命令工具（contracts/assembly.md §1.1 第 6 行）。
   *
   * @param sandbox 校验对象是可执行文件名，不是整条命令
   * @return {@code shell} 的宿主
   */
  @Bean
  public ShellTools shellTools(Sandbox sandbox) {
    return new ShellTools(sandbox);
  }

  /**
   * HTTP 工具（contracts/assembly.md §1.1 第 7 行）。
   *
   * @param sandbox 校验对象是完整 URL 的域名部分
   * @return {@code http_get} / {@code http_post} 的宿主
   */
  @Bean
  public HttpTools httpTools(Sandbox sandbox) {
    return new HttpTools(sandbox);
  }

  /**
   * 交互接缝的档位选择（contracts/assembly.md §1.1 第 8 行）。
   *
   * <p><b>重命令一律挂终端档</b>：{@code chat} / {@code serve} / {@code gateway} 都有人在终端或另一端，{@code ask_user}
   * 能真的问出去。挂 {@code UnsupportedUserInteraction} 的场合是「压根没有人」（定时任务、无状态调用），那两处的装配归第 25 / 26 节。
   *
   * <p><b>两档实现都在，装配处显式选一档</b>——这正是 FR-017 要的形状：工具只负责递问题、带回答，「能不能问」由这里决定。
   *
   * @return 读终端的交互实现
   */
  @Bean
  public UserInteraction userInteraction() {
    return new ConsoleUserInteraction();
  }

  /**
   * 交互工具（contracts/assembly.md §1.1 第 9 行）。
   *
   * @param userInteraction 当前渠道的交互档位
   * @return {@code ask_user} 的宿主
   */
  @Bean
  public InteractionTools interactionTools(UserInteraction userInteraction) {
    return new InteractionTools(userInteraction);
  }

  /**
   * 搜索接缝的实现（contracts/assembly.md §1.1 第 10 行）。
   *
   * <p><b>换搜索引擎只改这一行</b>（FR-018）：接缝上多了 {@code endpoint()}，故换实现连「目标 URL 从哪来」都不用在这里操心——
   * 新实现自己报出它的端点，{@code web_search} 与沙箱校验位零改动。
   *
   * <p><b>按 key 有无分流，而不是按 profile</b>：配了 {@code TAVILY_API_KEY} 就用带 key 的 Tavily（长尾查询能真搜到结果），
   * 没配就退回免 key 的 DuckDuckGo——「装上就能跑」这条核心阶段原则不破，装完不配任何东西也能用 {@code web_search}。 两个实现都是 {@code
   * SearchProvider}，所以换档对 {@code WebSearchTools}、沙箱校验位、工具注册表全是零改动。
   *
   * @param tavilyApiKey Tavily 的 key；由 {@code application.yaml} 的 {@code ${TAVILY_API_KEY:}}
   *     占位解析，未设置时为空串
   * @return 配了 key 时是 Tavily 实现，否则是免 key 的 DuckDuckGo 实现
   */
  @Bean
  public SearchProvider searchProvider(
      @Value("${oryxos.search.tavily-api-key:}") String tavilyApiKey) {
    return tavilyApiKey == null || tavilyApiKey.isBlank()
        ? new DuckDuckGoSearchProvider()
        : new TavilySearchProvider(tavilyApiKey);
  }

  /**
   * 搜索工具（contracts/assembly.md §1.1 第 11 行）。
   *
   * @param sandbox 校验对象是 {@code searchProvider.endpoint()} 的域名
   * @param searchProvider 搜索引擎实现
   * @return {@code web_search} 的宿主
   */
  @Bean
  public WebSearchTools webSearchTools(Sandbox sandbox, SearchProvider searchProvider) {
    return new WebSearchTools(sandbox, searchProvider);
  }

  /**
   * MCP 配置加载器（contracts/assembly.md §1.1 第 12 行）。
   *
   * <p><b>传的是 {@code workspace.root()}，不是再叠一层 {@code .oryxos}</b>：{@code Workspace.root()}
   * 返回的<b>就是</b> {@code .oryxos/} 目录本身，加载器在它下面解析 {@code mcp_servers.yaml}。多叠一层会解析成 {@code
   * .oryxos/.oryxos/mcp_servers.yaml}，而「文件不存在 = 空清单」这条规则会把错误静默吞掉，表现为「MCP 永远连不上、且一条日志都没有」。
   *
   * <p><b>MUST NOT 给 {@code Workspace} 加 {@code mcpServersFile()} 方法</b>（contracts §4.7）：{@code
   * Workspace} 是第 18 节 交付物，路径口径收在加载器内部。
   *
   * @param workspace 工作区，取其 {@code .oryxos/} 目录
   * @return 读 {@code mcp_servers.yaml} 的加载器
   */
  @Bean
  public McpServerConfigLoader mcpServerConfigLoader(Workspace workspace) {
    return new McpServerConfigLoader(workspace.root());
  }

  /**
   * MCP 客户端服务（contracts/assembly.md §1.1 第 13 行）。
   *
   * <p>只在 {@link #toolRegistry} 里被调一次，故它的 Bean 声明位置只影响可读性；真正决定成败的是 {@code connectAll} 的<b>调用时机</b>
   * ——见 {@link #toolRegistry}。
   *
   * @param mcpServerConfigLoader MCP 配置加载器
   * @return 连接与注册 MCP 工具的服务
   */
  @Bean
  public McpClientService mcpClientService(McpServerConfigLoader mcpServerConfigLoader) {
    return new McpClientService(mcpServerConfigLoader);
  }

  /**
   * 工具注册表：三源汇聚的唯一落点（contracts/assembly.md §1.1 第 14 行）。
   *
   * <p><b>声明位置 MUST 在本类的 {@code toolCatalog()} 之前</b>——理由与失败形态见 {@link #toolCatalog()}。
   *
   * <p><b>注册顺序即重名判定顺序</b>（FR-023）：内置与注解 Bean 先、MCP 后，故重名时内置工具胜出。本方法体内先登记各工具 Bean、最后才连
   * MCP，顺序写在代码里而不是靠装配顺序的巧合。
   *
   * <p><b>{@code connectAll} 只能在这里调，不能挪进 {@code mcpClientService()} 的 Bean 方法体</b>：那时注册表还没造出来。 而
   * {@code @Bean} 方法之间的依赖是 Spring 按类型解析的，把「先注册内置、后注册 MCP」这条顺序表达成方法体内的语句，比表达成 Bean
   * 声明顺序更难写错——声明顺序变了顺序还在。
   *
   * <p><b>加一个工具的成本恒定在「写一个类 + 本方法加一行」</b>（FR-022）：安全校验位、审计落库、按 Agent 过滤三处代码 一行都不用动——校验在工具自己的 {@code
   * enforce} 首行、审计在 {@code ToolExecutor}、过滤在 {@link ToolRegistry#availableFor}。
   *
   * @param notifyTools 通知工具（第 19 节挂账，本节注册）
   * @param fileTools 文件工具
   * @param shellTools 命令工具
   * @param httpTools HTTP 工具
   * @param interactionTools 交互工具
   * @param webSearchTools 搜索工具
   * @param memoryTools 记忆工具（第 22 节交付物，本节注册）
   * @param mcpClientService 连接 MCP server 并把它们的工具注册进本注册表
   * @return 注册表
   */
  @Bean
  public ToolRegistry toolRegistry(
      NotifyTools notifyTools,
      FileTools fileTools,
      ShellTools shellTools,
      HttpTools httpTools,
      InteractionTools interactionTools,
      WebSearchTools webSearchTools,
      MemoryTools memoryTools,
      McpClientService mcpClientService) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(notifyTools, "builtin");
    registry.registerAnnotated(fileTools, "builtin");
    registry.registerAnnotated(shellTools, "builtin");
    registry.registerAnnotated(httpTools, "builtin");
    registry.registerAnnotated(interactionTools, "builtin");
    registry.registerAnnotated(webSearchTools, "builtin");
    registry.registerAnnotated(memoryTools, "builtin");
    // 最后才连 MCP：重名时保留先注册的内置工具（FR-023）。失联的 server 在这里被 WARN + 跳过，不拖垮启动（FR-010）
    mcpClientService.connectAll(registry);
    return registry;
  }

  /**
   * 工具查询接缝的空实现（spec 仲裁 8 / FR-020）。
   *
   * <p><b>{@code @ConditionalOnMissingBean} 是让位机制</b>：第 20 节把 {@code ToolRegistry} 注册成同类型 Bean 后，本
   * Bean 自动不再创建，<b>不需要回头删代码</b>。
   *
   * <p><b>MUST NOT 用 {@code @Primary} 标在空实现上</b>——那是「永远优先用假的」，正好反过来，真实实现会被永久遮蔽。
   *
   * <p><b>声明顺序是契约</b>：{@code toolRegistry()} MUST 声明在本方法<b>之前</b>。{@code @ConditionalOnMissingBean}
   * 在同一个 {@code @Configuration} 类里按 {@code @Bean} 方法的声明顺序求值——若本方法先求值，容器里还没有 {@code ToolCatalog}，
   * 空实现被注册；随后 {@code toolRegistry()} 无条件注册，于是容器里出现<b>两个</b> {@code ToolCatalog}，{@code
   * PromptBuilder} / {@code ToolExecutor} 注入时抛 {@code
   * NoUniqueBeanDefinitionException}。这个错误<b>只在重命令（起上下文）时才炸</b>， {@code --help} / {@code --version}
   * 扫不出来（contracts/assembly.md §2）。
   *
   * @return 查不到任何工具的目录
   */
  @Bean
  @ConditionalOnMissingBean(ToolCatalog.class)
  public ToolCatalog toolCatalog() {
    return new EmptyToolCatalog();
  }

  /**
   * 长期记忆后端的选择点（第 22 节交付物；contracts/memory-config.md §3）。
   *
   * <p><b>换后端只改这一处配置</b>（SC-004）：{@code oryxos.memory.backend} 决定造哪一档 {@link LongTermMemoryStore}，
   * 而门面以上的代码——{@code PromptBuilder} / {@code MemoryTools} / {@code ReActLoop}——<b>一行都不改</b>。这正是第 21
   * 节 「接口先行、墙之下可以随时换实现」那句话的兑现。
   *
   * <p><b>声明位置 MUST 在本类的 {@code memorySource()} 之前</b>——理由与失败形态见 {@link #memorySource()}。
   *
   * <p><b>用经典冒号 {@code switch} 而不是箭头 switch / switch 表达式</b>：PMD 6.55 的 ASM 解析不了那些形态，静态检查是构建门禁。
   *
   * <p><b>未知取值 MUST 报错并列出合法取值，MUST NOT 静默退回默认档</b>（FR-016）：运维改了配置、重启、以为生效了，实际还在用旧的 ——那比启动失败更难查。
   *
   * <p><b>{@code MemoryEntryRepository} 无条件注入是安全的</b>：Spring Data 的 Repository Bean 是代理，创建时不碰数据库；
   * 默认档 {@code markdown} 下它一次都不会被调用。若改成「按需查找」反而要在装配处写条件分支，把一档的存在与否变成运行期问题。
   *
   * <p><b>{@code mem0} 分支的地址校验是唯一的拦截点</b>：配置侧两个子键都带默认值（{@code ${MEM0_BASE_URL:}} 是空串），
   * 缺失时配置加载本身永远不会失败，故校验推迟到「真的选中 mem0 档」这一刻（FR-018）。
   *
   * @param workspace 工作区，Markdown 档取它的 {@code .oryxos/} 目录
   * @param memoryEntryRepository {@code memory_entries} 表的存取出口，只有 {@code sqlite} 档会用到
   * @param backend 后端档位：{@code markdown}（默认）/ {@code sqlite} / {@code mem0}
   * @param mem0BaseUrl Mem0 服务地址（含凭证）；仅 {@code mem0} 档读取
   * @param mem0UserId 记忆归属的用户标识；仅 {@code mem0} 档读取
   * @return 选中的那一档后端之上的门面
   */
  @Bean
  public MemoryService memoryService(
      Workspace workspace,
      MemoryEntryRepository memoryEntryRepository,
      @Value("${oryxos.memory.backend:markdown}") String backend,
      @Value("${oryxos.memory.mem0.base-url:}") String mem0BaseUrl,
      @Value("${oryxos.memory.mem0.user-id:oryxos}") String mem0UserId) {
    LongTermMemoryStore store;
    switch (backend) {
      case "markdown":
        store = new MarkdownMemoryStore(workspace.root());
        break;
      case "sqlite":
        store = new SqliteMemoryStore(memoryEntryRepository);
        break;
      case "mem0":
        store = new Mem0MemoryStore(requireMem0BaseUrl(mem0BaseUrl), mem0UserId);
        break;
      default:
        throw new IllegalArgumentException(
            "未知的长期记忆后端 \"" + backend + "\"（合法取值：markdown / sqlite / mem0）");
    }
    return new MemoryServiceImpl(store);
  }

  /**
   * 记忆工具（第 22 节交付物）：{@code save_memory} / {@code recall_memory}。
   *
   * <p><b>参数是宽门面而不是 core 的窄接缝</b>：工具本来就要读写，而 {@code PromptBuilder} 收的是 {@code MemorySource}
   * ——只看得见「读」。给 prompt 组装器两个写入方法等于给它越界的能力。
   *
   * @param memoryService 读写长期记忆的门面
   * @return 记忆工具 Bean
   */
  @Bean
  public MemoryTools memoryTools(MemoryService memoryService) {
    return new MemoryTools(memoryService);
  }

  /**
   * Mem0 档的地址校验：缺失时抛异常，消息<b>只带变量名、不带值</b>。
   *
   * <p>与 {@code ProfileLoader.resolveEnv} / {@code JpaNotifyChannelRegistry} 同口径（都抛异常），
   * <b>刻意不同于</b> {@code McpServerConfigLoader} 的「跳过 + WARN」——那里一个 server 配错不该拖垮别的 server，
   * 这里长期记忆只有一个后端，配错<b>必须</b>让调用失败。
   *
   * @param mem0BaseUrl 配置里解析出来的地址
   * @return 原值
   */
  private static String requireMem0BaseUrl(String mem0BaseUrl) {
    if (mem0BaseUrl == null || mem0BaseUrl.isBlank()) {
      throw new IllegalArgumentException("长期记忆后端 mem0 需要环境变量 MEM0_BASE_URL，当前未设置");
    }
    return mem0BaseUrl;
  }

  /**
   * 长期记忆接缝的空实现（spec 仲裁 8 / FR-020）；让位机制同上。
   *
   * <p><b>第 22 节起它自动让位</b>：{@link #memoryService} 返回的 {@code MemoryServiceImpl} 也是 {@code
   * MemorySource}， 且那个方法声明在本方法<b>之前</b>，故本方法求值时容器里已有 {@code MemorySource}，空实现不再创建。<b>本方法一行不改</b>
   * （MUST NOT 手工删除、MUST NOT 加 {@code @Primary}）——这正是「让位机制」这四个字的兑现。
   *
   * @return 永远没有长期记忆的来源
   */
  @Bean
  @ConditionalOnMissingBean(MemorySource.class)
  public MemorySource memorySource() {
    return new EmptyMemorySource();
  }

  /**
   * {@link ToolCatalog} 的空实现：工具尚未就位时也要能跑通对话（FR-011）。
   *
   * <p>包级私有嵌套在这里，<b>不进 public 面</b>；也 MUST NOT 被命令类硬 {@code new}——那样第 20 节落地时要手改，容易漏。
   */
  private static final class EmptyToolCatalog implements ToolCatalog {

    @Override
    public Optional<OryxTool> find(String name) {
      return Optional.empty();
    }

    @Override
    public List<OryxTool> availableFor(Profile profile) {
      return List.of();
    }
  }

  /** {@link MemorySource} 的空实现：长期记忆尚未就位时，prompt 的第二部分整段跳过，不塞占位文本。 */
  private static final class EmptyMemorySource implements MemorySource {

    @Override
    public Optional<String> longTermMemory(Profile profile) {
      return Optional.empty();
    }
  }
}
