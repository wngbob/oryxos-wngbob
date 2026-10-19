package io.oryxlabs.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

/**
 * {@link AgentScheduler} 的验收测试（课件第 25 节「四、验收 harness」）。
 *
 * <p>守护的五条：注册时带上了配置的 cron <b>和时区</b>；上一次没跑完时本次触发<b>直接跳过</b>（零调用，不是 「调用了但被拒」）；执行内部抛异常<b>不外抛</b>且锁在
 * finally 里<b>真的被释放</b>（二进宫断言）；会话三元组固定为 {@code ("scheduler", "scheduler",
 * profileName)}；单条规则失败不影响其它规则与其它 Agent。
 *
 * <p>全程替身与手写桩，无 {@code Thread.sleep}、无真实时钟、不起 Spring 上下文。
 */
class AgentSchedulerTest {

  /** 固定时钟：2026-10-18T00:00:00Z——时区断言的「现在」，全程不碰真实时钟。 */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);

  private CapturingTaskScheduler capturing;
  private AgentService agentService;
  private SessionService sessionService;
  private Session session;

  @BeforeEach
  void setUp() {
    capturing = new CapturingTaskScheduler();
    agentService = mock(AgentService.class);
    sessionService = mock(SessionService.class);
    session = Session.create("scheduler:scheduler:weather", "scheduler", "scheduler", "weather");
    when(sessionService.getOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
  }

  /** 每个用例自带 Profile 集合：注册行为随输入而变，放 {@code @BeforeEach} 里只会让「输入是什么」看不清。 */
  private AgentScheduler newScheduler(Profile... profiles) {
    return new AgentScheduler(
        capturing, new ProfileRegistry(List.of(profiles)), agentService, sessionService);
  }

  /** {@code Profile} 是 10 参 record，本测试只关心名字与 schedules，其余传 null 走紧凑构造器归一化。 */
  private static Profile profile(String name, List<Profile.ScheduleSpec> schedules) {
    return new Profile(name, null, null, null, null, null, null, schedules, null, null);
  }

  /** 直接调 {@code runOnce} 用的配置：锁测试只关心 id，cron / zone / message 给固定值。 */
  private static ScheduleConfig config(String id) {
    return new ScheduleConfig(id, "0 9 * * *", null, "每日例行汇报");
  }

  /**
   * 在另一个线程上执行捕获的触发回调。MUST 跨线程：{@code ReentrantLock} 可重入，同一线程占着锁再 {@code tryLock()}
   * 照样成功，锁接缝的观测就失效了。{@code start + join} 是确定性交接， 不是 {@code Thread.sleep} 造时序。
   */
  private static void runOnOtherThread(Runnable runnable) throws InterruptedException {
    Thread worker = new Thread(runnable);
    worker.start();
    worker.join();
  }

  @Test
  void registerAll_registersOneTaskPerScheduleEntry() {
    // 三条全用 6 段（原样透传）：本用例守「一条规则一次注册 + 表达式忠实」，5→6 归一化有专测
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 0 9 * * *", "Asia/Shanghai", "早报"),
                new Profile.ScheduleSpec("0 30 18 * * *", null, "晚报"),
                new Profile.ScheduleSpec("0 0 12 * * 1-5", null, "午报")));

    newScheduler(weather).registerAll();

    assertThat(capturing.triggers()).hasSize(3);
    assertThat(((CronTrigger) capturing.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
    assertThat(((CronTrigger) capturing.triggers().get(1)).getExpression())
        .isEqualTo("0 30 18 * * *");
    assertThat(((CronTrigger) capturing.triggers().get(2)).getExpression())
        .isEqualTo("0 0 12 * * 1-5");
  }

  @Test
  void registerAll_withNoSchedules_registersNothingAndDoesNotThrow() {
    // 默认配置下的预期状态：零注册不是缺陷
    assertDoesNotThrow(() -> newScheduler(profile("quiet", List.of())).registerAll());
    assertThat(capturing.triggers()).isEmpty();
  }

  @Test
  void registerAll_derivesIdsDeterministically() throws InterruptedException {
    // 锁接缝反推：占住预期 id 的锁后执行捕获的 Runnable，被跳过即证明 id 就是 weather#1
    Profile weather =
        profile("weather", List.of(new Profile.ScheduleSpec("0 9 * * *", null, "早报")));
    AgentScheduler scheduler = newScheduler(weather);

    ReentrantLock lock = scheduler.lockFor("weather#1");
    lock.lock();
    try {
      scheduler.registerAll();
      runOnOtherThread(capturing.tasks().get(0));
      verify(agentService, never()).process(any(), any());

      capturing.tasks().clear();
      scheduler.registerAll();
      runOnOtherThread(capturing.tasks().get(0));
      verify(agentService, never()).process(any(), any());
    } finally {
      lock.unlock();
    }
  }

  @Test
  void registerAll_doesNotDeduplicateIdenticalRules() throws InterruptedException {
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", null, "早报"),
                new Profile.ScheduleSpec("0 9 * * *", null, "早报")));
    AgentScheduler scheduler = newScheduler(weather);

    scheduler.registerAll();
    assertThat(capturing.triggers()).hasSize(2);

    ReentrantLock lock = scheduler.lockFor("weather#1");
    lock.lock();
    try {
      runOnOtherThread(capturing.tasks().get(0));
      runOnOtherThread(capturing.tasks().get(1));
      // 第一个被 #1 的锁跳过、第二个照常执行 = 第二个的 id 是 #2，不是 #1
      verify(agentService, times(1)).process(any(), any());
    } finally {
      lock.unlock();
    }
  }

  @Test
  void registerAll_normalizesFiveAndPassesSixFieldCronInSameProfile() {
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", null, "五段"),
                new Profile.ScheduleSpec("0 30 8 * * *", null, "六段")));

    newScheduler(weather).registerAll();

    assertThat(capturing.triggers()).hasSize(2);
    assertThat(((CronTrigger) capturing.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
    assertThat(((CronTrigger) capturing.triggers().get(1)).getExpression())
        .isEqualTo("0 30 8 * * *");
  }

  @Test
  void registerAll_rejectsInvalidCronFieldCount_andKeepsOtherRules() {
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 9 * *", null, "四段坏"),
                new Profile.ScheduleSpec("0 9 * * *", null, "五段好")));
    Profile tech = profile("tech", List.of(new Profile.ScheduleSpec("0 0 12 * * *", null, "别家好")));

    newScheduler(weather, tech).registerAll();

    // 坏的那条不注册，同 Profile 的另一条与另一个 Agent 照常
    assertThat(capturing.triggers()).hasSize(2);
    assertThat(((CronTrigger) capturing.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
    assertThat(((CronTrigger) capturing.triggers().get(1)).getExpression())
        .isEqualTo("0 0 12 * * *");
  }

  @Test
  void registerAll_rejectsMissingCron_andKeepsOtherRules() {
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec(null, null, "没写 cron"),
                new Profile.ScheduleSpec("   ", null, "全是空白"),
                new Profile.ScheduleSpec("0 9 * * *", null, "好的")));

    newScheduler(weather).registerAll();

    assertThat(capturing.triggers()).hasSize(1);
    assertThat(((CronTrigger) capturing.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
  }

  @Test
  void registerAll_rejectsSyntacticallyInvalidCron_andKeepsOtherRules() {
    // 段数对但内容非法：CronTrigger 构造时才抛，被同一层 catch 接住
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 0 99 * * *", null, "内容非法"),
                new Profile.ScheduleSpec("0 9 * * *", null, "好的")));

    newScheduler(weather).registerAll();

    assertThat(capturing.triggers()).hasSize(1);
    assertThat(((CronTrigger) capturing.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
  }

  @Test
  void runOnce_callsProcessWithConfiguredMessage() {
    Profile weather = profile("weather", List.of());
    ScheduleConfig config = config("weather#1");

    newScheduler(weather).runOnce(weather, config);

    verify(agentService, times(1)).process(session, config.message());
  }

  @Test
  void runOnce_usesFixedSchedulerSessionIdentity() {
    Profile weather = profile("weather", List.of());

    newScheduler(weather).runOnce(weather, config("weather#1"));

    verify(sessionService).getOrCreate("scheduler", "scheduler", "weather");
  }

  @Test
  void runOnce_reusesSameSessionAcrossTriggers() {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);

    scheduler.runOnce(weather, config("weather#1"));
    scheduler.runOnce(weather, config("weather#1"));

    // 两次触发按同一个三元组取会话，拿到同一个会话
    verify(sessionService, times(2)).getOrCreate("scheduler", "scheduler", "weather");
    verify(agentService, times(2)).process(session, "每日例行汇报");
  }

  @Test
  @DisplayName("注册时 CronTrigger 带上了配置的 cron 和时区")
  void registerAll_passesConfiguredCronAndZone() {
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", "Asia/Shanghai", "上海"),
                new Profile.ScheduleSpec("0 9 * * *", "UTC", "UTC")));

    newScheduler(weather).registerAll();

    assertThat(capturing.triggers()).hasSize(2);
    TriggerContext context = new FixedClockTriggerContext(FIXED_CLOCK);
    Trigger shanghai = capturing.triggers().get(0);
    Trigger utc = capturing.triggers().get(1);

    // cron 忠实携带（5 段归一化成 6 段）
    assertThat(((CronTrigger) shanghai).getExpression()).isEqualTo("0 0 9 * * *");
    assertThat(((CronTrigger) utc).getExpression()).isEqualTo("0 0 9 * * *");

    Instant shanghaiNext = shanghai.nextExecution(context);
    Instant utcNext = utc.nextExecution(context);

    // 落点在配置时区下是 09:00
    assertThat(shanghaiNext.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime())
        .isEqualTo(LocalTime.of(9, 0));
    assertThat(utcNext.atZone(ZoneOffset.UTC).toLocalTime()).isEqualTo(LocalTime.of(9, 0));

    // 同一表达式配不同时区，落点 MUST 不同（相差 8 小时）。MUST NOT 改成比对 Trigger 相等——
    // CronTrigger.equals 忽略时区（research §0.2 实测），那条断言恒绿、什么都守不住
    assertThat(shanghaiNext).isNotEqualTo(utcNext);
    assertThat(Duration.between(shanghaiNext, utcNext)).isEqualTo(Duration.ofHours(8));
  }

  @Test
  void registerAll_zoneAbsent_fallsBackToSystemDefault_withExplicitZone() {
    String systemZone = ZoneId.systemDefault().getId();
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", null, "缺省"),
                new Profile.ScheduleSpec("0 9 * * *", systemZone, "显式系统时区")));

    newScheduler(weather).registerAll();

    // 缺省 zone 不拒绝注册（场景 4）；且缺省与「显式写出系统时区」落点相同——
    // 缺省走的就是系统时区，且是显式取出来传进去的，不是框架的隐含行为
    assertThat(capturing.triggers()).hasSize(2);
    TriggerContext context = new FixedClockTriggerContext(FIXED_CLOCK);
    assertThat(capturing.triggers().get(0).nextExecution(context))
        .isEqualTo(capturing.triggers().get(1).nextExecution(context));
  }

  @Test
  void registerAll_rejectsInvalidZone_andKeepsOtherRules() {
    Profile weather =
        profile(
            "weather",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", "Mars/Olympus", "坏时区"),
                new Profile.ScheduleSpec("0 9 * * *", null, "好的")));

    newScheduler(weather).registerAll();

    assertThat(capturing.triggers()).hasSize(1);
    assertThat(((CronTrigger) capturing.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
  }

  @Test
  @DisplayName("上一次还没跑完_本次触发直接跳过")
  void runOnce_skipsWhenTaskLockHeld() throws InterruptedException {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);

    // 占住 task-1 的锁 = 上一次还没跑完；runOnce MUST 从另一线程调——ReentrantLock 可重入，
    // 本线程占着锁再 tryLock() 照样成功，「跳过」这条路径在同线程里根本走不到
    ReentrantLock lock = scheduler.lockFor("task-1");
    lock.lock();
    try {
      runOnOtherThread(() -> scheduler.runOnce(weather, config("task-1")));
      // 「压根没发生」，不是「调用了但被拒」
      verify(agentService, never()).process(any(), any());
    } finally {
      lock.unlock();
    }
  }

  @Test
  void runOnce_proceedsAfterLockReleased() throws InterruptedException {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);

    ReentrantLock lock = scheduler.lockFor("task-1");
    lock.lock();
    try {
      runOnOtherThread(() -> scheduler.runOnce(weather, config("task-1")));
      verify(agentService, never()).process(any(), any());
    } finally {
      lock.unlock();
    }

    // 放锁后再触发，照常进来
    scheduler.runOnce(weather, config("task-1"));
    verify(agentService, times(1)).process(session, "每日例行汇报");
  }

  @Test
  void runOnce_doesNotBlockOtherTaskIds() {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);

    // 锁是按任务 id 分的，不是一把全局锁：task-1 被占不影响 task-2（不同锁，同线程直接调即可）
    ReentrantLock lock = scheduler.lockFor("task-1");
    lock.lock();
    try {
      scheduler.runOnce(weather, config("task-2"));
      verify(agentService, times(1)).process(session, "每日例行汇报");
    } finally {
      lock.unlock();
    }
  }

  @Test
  void runOnce_doesNotWaitOnHeldLock() {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);

    // 「不排队」的判据是耗时上界：实现若写成 lock.lock() 阻塞等待，占锁不放时这里会超时变红。
    // 不用 Thread.sleep 造时序，超时断言本身就是判据
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> {
          ReentrantLock lock = scheduler.lockFor("task-1");
          lock.lock();
          try {
            runOnOtherThread(() -> scheduler.runOnce(weather, config("task-1")));
          } finally {
            lock.unlock();
          }
        });
  }

  @Test
  @DisplayName("任务抛异常_不外抛")
  void runOnce_swallowsExceptionFromProcess() {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));

    assertDoesNotThrow(() -> scheduler.runOnce(weather, config("task-1")));
  }

  @Test
  @DisplayName("任务抛异常_不外抛且锁必须被释放")
  void runOnce_releasesLockAfterFailure_secondRunProceeds() {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);
    // 第一次抛、第二次正常：二进宫断言——只有锁真的在 finally 里放了，第二次才进得来
    when(agentService.process(any(), any()))
        .thenThrow(new RuntimeException("boom"))
        .thenReturn("ok");

    assertDoesNotThrow(() -> scheduler.runOnce(weather, config("task-1")));
    scheduler.runOnce(weather, config("task-1"));

    // times(2) 而非 times(1)：第一次调了并抛、第二次又调了。漏写 finally unlock() 的实现照样
    // 「不抛异常、日志照打」，但任务从此永久卡死——只有这条抓得住
    verify(agentService, times(2)).process(any(), any());
  }

  @Test
  void runOnce_failureIsIsolatedPerTaskId() {
    Profile weather = profile("weather", List.of());
    AgentScheduler scheduler = newScheduler(weather);
    // task-1 抛、task-2 正常：失败被隔离在单个任务内
    when(agentService.process(any(), any()))
        .thenThrow(new RuntimeException("boom"))
        .thenReturn("ok");

    scheduler.runOnce(weather, config("task-1"));
    scheduler.runOnce(weather, config("task-2"));

    verify(agentService, times(2)).process(any(), any());
  }

  @Test
  void runOnce_doesNotLogOrPersistAnythingItself() {
    // FR-011 的机器可判定形式：本节新增审计代码 0 行，失败落库全部经由 AgentService 内部既有路径
    String source =
        read("oryxos-core/src/main/java/io/oryxlabs/oryxos/core/schedule/AgentScheduler.java");
    assertThat(source).doesNotContain("llm_calls");
    assertThat(source).doesNotContain("tool_invocations");
    assertThat(source).doesNotContain("LlmCall");
    assertThat(source).doesNotContain("ToolInvocation");
  }

  private static String read(String relativePath) {
    Path file = repoRoot().resolve(relativePath);
    if (!Files.isRegularFile(file)) {
      fail("找不到源文件（仓库布局变了？）：" + file);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException("读不了源文件: " + file, e);
    }
  }

  /** 从模块目录（surefire 的 cwd）往上找仓库根；找不到直接失败，不静默跳过。 */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-boot")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }

  /**
   * 手写桩：按注册顺序回放 {@code (Runnable, Trigger)} 对。不用 Mockito——两个参数都要留下来做断言。 本节的实现只用 {@code
   * schedule(Runnable, Trigger)} 这一个方法，其余 5 个抽象方法抛异常防误用 （{@code Date} / {@code long} 变体是 default
   * 方法、委托给这几个抽象方法，不需要桩）。
   */
  private static final class CapturingTaskScheduler implements TaskScheduler {

    private final List<Trigger> triggers = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
      tasks.add(task);
      triggers.add(trigger);
      return null;
    }

    List<Trigger> triggers() {
      return triggers;
    }

    List<Runnable> tasks() {
      return tasks;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable task, Instant startTime, Duration period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable task, Instant startTime, Duration delay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * 手写桩：固定时钟 + 「从没跑过」（3 个抽象 {@code last*} 一律 null——{@code CronTrigger.nextExecution}
   * 的语义就是求下一次；{@code last*Time()} 变体是 default 方法、委托给这 3 个，不需要桩）。
   */
  private static final class FixedClockTriggerContext implements TriggerContext {

    private final Clock clock;

    FixedClockTriggerContext(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Clock getClock() {
      return clock;
    }

    @Override
    public Instant lastScheduledExecution() {
      return null;
    }

    @Override
    public Instant lastActualExecution() {
      return null;
    }

    @Override
    public Instant lastCompletion() {
      return null;
    }
  }
}
