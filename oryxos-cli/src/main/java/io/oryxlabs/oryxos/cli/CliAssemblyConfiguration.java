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
import io.oryxlabs.oryxos.provider.JpaLlmCallRecorder;
import io.oryxlabs.oryxos.provider.LlmCallRecorder;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import io.oryxlabs.oryxos.provider.ProviderService;
import io.oryxlabs.oryxos.provider.ToolSchemaAdapter;
import io.oryxlabs.oryxos.storage.repository.LlmCallRepository;
import io.oryxlabs.oryxos.storage.repository.SessionRepository;
import io.oryxlabs.oryxos.storage.repository.ToolInvocationRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重命令的 Bean 装配——<b>全部 {@code @Bean} 的唯一落点</b>（contracts/cli-assembly.md §3 / §4 / §5）。
 *
 * <p><b>为什么集中在一处而不是给每个类加 {@code @Component}</b>：core 与能力模块的类型刻意不带 Spring 注解（它们是可单测的纯 Java 对象，第
 * 16/17 节的测试全部手工 {@code new}）。「谁被装配、装配成什么」只在这一处可见，第 20 / 22 / 26 节加 Bean 时改这一个类； 散到各模块就变成多处真相，且
 * core 会反向依赖 Spring 的装配语义。
 *
 * <p><b>12 个命令类 MUST NOT 自己 {@code new} 服务</b>：重命令从上下文取 Bean；轻命令走 {@code workspace/} 子包里的文件与 JDBC
 * 辅助类（它们根本不启动上下文）。
 */
@Configuration
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
   * 工具查询接缝的空实现（spec 仲裁 8 / FR-020）。
   *
   * <p><b>{@code @ConditionalOnMissingBean} 是让位机制</b>：第 20 节把 {@code ToolRegistry} 注册成同类型 Bean 后，本
   * Bean 自动不再创建，<b>不需要回头删代码</b>。
   *
   * <p><b>MUST NOT 用 {@code @Primary} 标在空实现上</b>——那是「永远优先用假的」，正好反过来，真实实现会被永久遮蔽。
   *
   * @return 查不到任何工具的目录
   */
  @Bean
  @ConditionalOnMissingBean(ToolCatalog.class)
  public ToolCatalog toolCatalog() {
    return new EmptyToolCatalog();
  }

  /**
   * 长期记忆接缝的空实现（spec 仲裁 8 / FR-020）；让位机制同上。
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
