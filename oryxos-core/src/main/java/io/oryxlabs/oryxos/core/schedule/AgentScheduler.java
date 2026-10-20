package io.oryxlabs.oryxos.core.schedule;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 定时任务（钟推）：第三种触发源（技术方案 §8.5）。
 *
 * <p>到点了，系统自己拼一条消息，喂给与 CLI / Web <b>完全相同</b>的处理入口（{@link AgentService#process}）—— 本类只做「注册规则 → 到点触发
 * → 拼消息交出去」这一件事，消息怎么处理是引擎的事（章程原则 IX / FR-006）。
 *
 * <p><b>注册时机由命令体决定</b>：本类刻意<b>不带</b> {@code @Component} / {@code @PostConstruct}，注册只发生在 {@code
 * serve} / {@code gateway} 命令体里的显式 {@link #registerAll()} 调用——交互式的 {@code chat} 起一个 Spring
 * 上下文不该捎带注册定时任务（FR-012）。
 *
 * <p><b>重叠触发直接跳过</b>：同一任务上一次还没跑完时，本次触发拿不到锁就跳过——不排队、不等待、不并发跑两份 （FR-008）。锁按任务 id 分，不同任务互不阻塞。
 *
 * <p><b>第 28 节起任务有状态</b>：注册时把登记信息与算出的下次触发时刻写进 {@link ScheduledTaskStore}；每次真正执行把结果
 * 写进执行历史并滚动任务状态（成功失败都记）；到点先看启用状态，停用的任务直接跳过、不执行也不记历史； {@link #runNow(String)} 供管理台「立即执行」使用（手动触发，不等
 * cron、无视启用状态）。
 */
public class AgentScheduler {

  private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

  /** 5 段 Unix cron（{@code 分 时 日 月 周}）——本仓契约的基准方言（FR-003）。 */
  private static final int UNIX_CRON_FIELDS = 5;

  /** 6 段 Spring 原生 cron（{@code 秒 分 时 日 月 周}）——兼容方言，原样透传。 */
  private static final int SPRING_CRON_FIELDS = 6;

  /** 5 段归一化时前补的秒位（Spring 的 cron 方言第一段是秒）。 */
  private static final String SPRING_SECOND_FIELD = "0 ";

  private final TaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionService sessionService;
  private final ScheduledTaskStore taskStore;
  private final Clock clock;

  /**
   * 按任务 id 的进程内锁表。
   *
   * <p><b>只增不减</b>：任务永不结束，没有「跑完就删锁」这种事；条目数上界是 {@code schedules} 总条数， 不会内存泄漏（数据模型 §4 不变式
   * 3）。锁的持有者恒为触发线程：{@code tryLock} 成功 → {@code finally} 释放， 同一线程内成对出现（§4 不变式 4）。
   */
  private final Map<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

  /**
   * 已注册任务的内存索引：{@code runNow(taskId)} 要靠它反查「这条任务属于哪个 Agent、发什么消息」。
   *
   * <p>只在 {@link #registerAll()} 里写、只在 {@link #runNow(String)} 里读；用并发 Map 是因为注册发生在启动线程、手动执行发生在 HTTP
   * 线程。
   */
  private final Map<String, RegisteredTask> registeredTasks = new ConcurrentHashMap<>();

  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionService sessionService,
      ScheduledTaskStore taskStore) {
    this(
        taskScheduler,
        profileRegistry,
        agentService,
        sessionService,
        taskStore,
        Clock.systemDefaultZone());
  }

  /**
   * 测试接缝：固定时钟，让 {@code next_run_at} 可被断言。
   *
   * <p>包级私有——生产装配只该用上面那个公开构造器；时钟不是一个对外配置项。
   */
  AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionService sessionService,
      ScheduledTaskStore taskStore,
      Clock clock) {
    this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
    this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry");
    this.agentService = Objects.requireNonNull(agentService, "agentService");
    this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
    this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 扫描全部已装载 Profile 的 {@code schedules} 段，逐条注册进调度器并登记到持久层。
   *
   * <p>任务 id 由「Profile 名 + 配置内序号（从 1 起）」确定性派生（FR-013）；<b>不去重</b>——两条完全相同的规则 派生两个 id、各自注册（去重是静默行为）。
   *
   * <p><b>单条失败隔离</b>：任何一条规则失败（cron 段数 / 内容非法、缺失、持久层写失败）只打 ERROR 指名 Agent 与任务后跳过， 不中断注册流程——同 Profile
   * 的其它规则照常、其它 Agent 不受影响（FR-004）。
   *
   * <p><b>先登记、后调度</b>：顺序不能反——调度一旦生效，到点就会执行并写执行历史；历史行指向一条还没登记的任务时，
   * 管理台会看到「有历史、没有任务」的孤儿记录。登记失败则这条规则整条不调度，错误在启动日志里可见。
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "三个占位实参都已过 sanitizeForLog；FindSecBugs 之所以仍报，是因为同一次调用还带了异常对象"
              + "（契约 §1.3 要求「+ 异常对象」，栈里的原始消息由 logback 渲染、不参与 SLF4J 的格式化），"
              + "而本仓既有的 GlobalExceptionHandler.internalError 也是同样写法（log.error(\"internal error\", ex)）。"
              + "就地豁免并留下理由，比在全局 excludeFilterFile 里开口子更可审计")
  public void registerAll() {
    int registered = 0;
    for (Profile profile : profileRegistry.all()) {
      int seq = 0;
      for (Profile.ScheduleSpec spec : profile.schedules()) {
        seq++;
        ScheduleConfig config =
            new ScheduleConfig(
                profile.name() + "#" + seq, spec.cron(), spec.zone(), spec.message());
        try {
          String cron = normalizeCron(config.cron());
          ZoneId zone = resolveZone(config.zone());
          Instant nextRunAt = nextRunAt(cron, zone, clock.instant());
          taskStore.register(
              new ScheduledTaskView(
                  config.id(),
                  profile.name(),
                  config.cron(),
                  config.zone(),
                  config.message(),
                  true,
                  nextRunAt,
                  null,
                  null,
                  0));
          taskScheduler.schedule(() -> trigger(profile, config), new CronTrigger(cron, zone));
          registeredTasks.put(config.id(), new RegisteredTask(profile, config));
          registered++;
        } catch (RuntimeException e) {
          // 拼串而非占位符：走 error(String, Throwable) 重载，避免 FindSecBugs 对变参重载的 CRLF 告警
          log.error(
              "定时任务注册失败（Agent {}，任务 {}）：{}",
              sanitizeForLog(profile.name()),
              sanitizeForLog(config.id()),
              sanitizeForLog(e.getMessage()),
              e);
        }
      }
    }
    log.info("定时任务注册完成：共 {} 条", registered);
  }

  /**
   * 到点触发：先看启用状态，再执行。
   *
   * <p><b>停用即跳过</b>：{@code enabled=false} 的任务不执行、不记历史——「没跑」与「跑了但失败」在运维眼里必须分得开。 手动「立即执行」走 {@link
   * #runNow(String)}，那条路径刻意<b>不看</b>启用状态。
   *
   * <p>读启用状态失败时<b>按启用处理</b>：这是任务执行路径，持久层短暂抖动不该让所有定时任务静默停摆；失败会在 ERROR 日志里可见，下一次触发会自动恢复。
   */
  private void trigger(Profile profile, ScheduleConfig config) {
    boolean enabled;
    try {
      enabled = taskStore.isEnabled(config.id());
    } catch (RuntimeException e) {
      log.error(
          "定时任务 {} 的启用状态读取失败，本次按启用处理：{}",
          sanitizeForLog(config.id()),
          sanitizeForLog(e.getMessage()));
      enabled = true;
    }
    if (!enabled) {
      log.debug("定时任务 {} 已停用，本次触发跳过", sanitizeForLog(config.id()));
      return;
    }
    runOnce(profile, config);
  }

  /**
   * 一次触发的全部动作：占锁 → 取或建会话 → 调统一处理入口 → 记执行历史。
   *
   * <p>拿不到锁（上一次还没跑完）就打 WARN 直接返回——{@code tryLock()} 无参、不等待（FR-008 / US3）。
   *
   * <p><b>执行历史在锁释放之后写</b>：历史落库属于结算，不是任务本身；把它放在锁内会让一次慢 SQL 阻塞下一次触发。 成功失败都写，失败原因用 `{@code
   * Exception#getMessage()}`（人能读的那句），不外抛——调度线程不能死。
   *
   * <p>包级私有：测试接缝（US3 占锁 / US4 异常隔离的判定入口），不进对外概念。
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "同 registerAll：占位实参已过 sanitizeForLog，剩余面是契约 §1.3 要求带的异常对象（栈由 logback 渲染）")
  void runOnce(Profile profile, ScheduleConfig config) {
    ReentrantLock lock = lockFor(config.id());
    if (!lock.tryLock()) {
      log.warn("定时任务 {} 上一次尚未结束，本次触发跳过", sanitizeForLog(config.id()));
      return;
    }
    Instant startedAt = clock.instant();
    Session session = null;
    boolean success = false;
    String errorMessage = null;
    try {
      session = sessionService.getOrCreate("scheduler", "scheduler", profile.name());
      agentService.process(session, config.message());
      success = true;
    } catch (Exception e) {
      // 失败不外抛（调度线程不死）、不静默（ERROR 是唯一留痕载体）——审计落库走 AgentService 内部既有路径，
      // 本类一行 LLM / Tool 审计代码都没有（FR-006 / FR-009 / FR-011）。catch 宽度是 Exception：模型 / 工具 /
      // 网络任何失败都不能让调度线程带着异常死掉
      errorMessage = e.getMessage();
      log.error("定时任务 {} 执行失败：{}", sanitizeForLog(config.id()), sanitizeForLog(errorMessage), e);
    } finally {
      lock.unlock();
    }
    recordExecution(config, session, startedAt, success, errorMessage);
  }

  /**
   * 手动触发一次（管理台「立即执行」）：不等 cron、<b>无视启用状态</b>。
   *
   * <p>同步执行：调用方（HTTP 线程）等到这次 ReAct 跑完才返回，返回体里的 {@code runCount} 已经是含本次的计数。 这与核心阶段的同步阻塞模型一致（章程原则
   * 8），也避免管理台点完按钮立刻查列表却看到旧计数。
   *
   * @param taskId 任务标识
   * @return 任务已注册并已触发返回 {@code true}；调度器里没有这条任务（例如它已从 {@code AGENT.md} 删掉）返回 {@code false}
   */
  public boolean runNow(String taskId) {
    RegisteredTask task = registeredTasks.get(taskId);
    if (task == null) {
      return false;
    }
    runOnce(task.profile(), task.config());
    return true;
  }

  /**
   * 写执行历史并滚动任务状态。
   *
   * <p>持久层写失败只记 ERROR、不外抛：一次落库失败不该把已经跑完的任务变成「调度器崩溃」。日志不带异常对象——这里的 失败原因已经在消息里，栈对运维的增量信息有限，而带对象会多一处
   * CRLF 豁免。
   */
  private void recordExecution(
      ScheduleConfig config, Session session, Instant startedAt, boolean success, String error) {
    try {
      String cron = normalizeCron(config.cron());
      ZoneId zone = resolveZone(config.zone());
      Instant nextRunAt = nextRunAt(cron, zone, clock.instant());
      long durationMs = Math.max(0L, Duration.between(startedAt, clock.instant()).toMillis());
      taskStore.recordExecution(
          new TaskExecutionView(
              null,
              config.id(),
              session == null ? null : session.id(),
              startedAt,
              success,
              error,
              durationMs),
          nextRunAt);
    } catch (RuntimeException e) {
      log.error(
          "定时任务 {} 的执行历史落库失败：{}", sanitizeForLog(config.id()), sanitizeForLog(e.getMessage()));
    }
  }

  /**
   * 入日志前替换 CR / LF：日志里出现的值（Profile 名、任务 id、异常消息）来自 Agent 配置，属<b>不可信输入</b>， 原样落日志可被用来伪造日志行（CWE-117
   * 日志注入）。与 {@code GlobalExceptionHandler.sanitizeForLog} 同款处理。
   */
  private static String sanitizeForLog(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /**
   * 取/建任务 id 对应的锁。
   *
   * <p>包级私有：测试占锁用（模拟「上一次还没跑完」）。
   */
  ReentrantLock lockFor(String taskId) {
    return taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
  }

  /**
   * cron 段数归一化：5 段 Unix cron（{@code 分 时 日 月 周}）前补秒位 {@code 0 } 成 6 段，6 段原样透传， 其余段数与缺失一律拒（FR-003 /
   * 数据模型 §3.1）。
   *
   * <p><b>归一化只发生在本类内部这一处</b>：不回写 Profile、不进 ProfileLoader、不在装配处预处理—— 报错消息里带的是用户写的那个表达式。
   */
  private static String normalizeCron(String cron) {
    if (cron == null || cron.isBlank()) {
      throw new IllegalArgumentException("cron 表达式缺失");
    }
    String trimmed = cron.trim();
    int fields = trimmed.split("\\s+").length;
    if (fields == UNIX_CRON_FIELDS) {
      return SPRING_SECOND_FIELD + trimmed;
    }
    if (fields == SPRING_CRON_FIELDS) {
      return trimmed;
    }
    throw new IllegalArgumentException(
        "cron 表达式段数非法：'"
            + cron
            + "'（支持 "
            + UNIX_CRON_FIELDS
            + " 段或 "
            + SPRING_CRON_FIELDS
            + " 段，实际 "
            + fields
            + " 段）");
  }

  /**
   * 时区解析：缺省（null / 空白）取系统时区，声明了就按声明的跑（FR-005）。
   *
   * <p><b>MUST 永远显式传出 ZoneId</b>：即使缺省也是 {@code ZoneId.systemDefault()} 显式取出再交给 {@code
   * CronTrigger}——单参构造的「用系统时区」是框架的隐含行为，单测对不上号（场景 4 的判定靠 「缺省 ≡ 显式写出系统时区」）。
   */
  private static ZoneId resolveZone(String zone) {
    if (zone == null || zone.isBlank()) {
      return ZoneId.systemDefault();
    }
    try {
      return ZoneId.of(zone.trim());
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("时区非法：'" + zone + "'", e);
    }
  }

  /**
   * 下一次触发时刻（落库的 {@code next_run_at}）。
   *
   * <p>与调度器同一份 cron 与同一份时区：先用 {@code CronExpression} 在配置时区下求下一次，再换算成绝对时刻。 {@code CronExpression}
   * 对「合法但永不触发」的表达式会返回 {@code null}（例如 2 月 30 日），此时按配置错误处理。
   */
  private static Instant nextRunAt(String cron, ZoneId zone, Instant now) {
    LocalDateTime next = CronExpression.parse(cron).next(LocalDateTime.ofInstant(now, zone));
    if (next == null) {
      throw new IllegalArgumentException("cron 表达式没有下一次触发时间：'" + cron + "'");
    }
    return next.atZone(zone).toInstant();
  }

  /**
   * 已注册任务的内存索引条目。
   *
   * @param profile 归属 Agent（执行时要拿它的 tools / provider）
   * @param config 该任务的调度视图
   */
  private record RegisteredTask(Profile profile, ScheduleConfig config) {}
}
