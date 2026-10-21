package io.oryxlabs.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 课件 harness 第 5 行「{@code AgentSchedulerRegisterTest}」的落地（第 29 节）。
 *
 * <p>守三件事：① {@code registerProfile} 之后句柄表里有对应任务 id 的句柄；② cron / 时区来自 {@code Profile.schedules}
 * （不是调度器的默认值）；③ 同一 Agent 重复注册是<b>替换</b>而非翻倍——旧句柄被 {@code cancel(false)}、句柄数不涨。
 */
class AgentSchedulerRegisterTest {

  /** 固定时钟：让「下一次触发」与 zone 的对应关系可被断言。 */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

  private final CapturingTaskScheduler taskScheduler = new CapturingTaskScheduler();
  private final ScheduledTaskStore taskStore = mock(ScheduledTaskStore.class);
  private final AgentService agentService = mock(AgentService.class);
  private final SessionService sessionService = mock(SessionService.class);
  private final ProfileRegistry profileRegistry = new ProfileRegistry(List.of());

  @Test
  @DisplayName(
      "AgentSchedulerRegisterTest：registerProfile 后 scheduledTasks 有句柄，cron / 时区来自 Profile")
  void registerProfile_schedulesEachRuleAndExposesHandles() {
    Profile recon =
        profile(
            "recon",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", "Asia/Shanghai", "早报"),
                new Profile.ScheduleSpec("0 30 18 * * *", "UTC", "晚报")));

    AgentScheduler scheduler = scheduler();
    int registered = scheduler.registerProfile(recon);

    assertThat(registered).isEqualTo(2);
    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("recon#1", "recon#2");
    assertThat(taskScheduler.triggers()).hasSize(2);

    Trigger morning = taskScheduler.triggers().get(0);
    Trigger evening = taskScheduler.triggers().get(1);
    // 5 段归一化成 6 段；6 段原样透传
    assertThat(((CronTrigger) morning).getExpression()).isEqualTo("0 0 9 * * *");
    assertThat(((CronTrigger) evening).getExpression()).isEqualTo("0 30 18 * * *");

    TriggerContext context = new FixedClockTriggerContext(FIXED_CLOCK);
    // 时区是显式传的：同一固定时钟下，两个 zone 的落点分别是当地 09:00 / 18:30
    assertThat(morning.nextExecution(context).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime())
        .isEqualTo(LocalTime.of(9, 0));
    assertThat(evening.nextExecution(context).atZone(ZoneOffset.UTC).toLocalTime())
        .isEqualTo(LocalTime.of(18, 30));
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：重复注册是替换——旧句柄被取消、句柄数不翻倍")
  void registerProfile_replacesPreviousHandles() {
    Profile first = profile("recon", List.of(new Profile.ScheduleSpec("0 9 * * *", "UTC", "旧早报")));
    AgentScheduler scheduler = scheduler();

    scheduler.registerProfile(first);
    // 快照：桩里的 handles() 是活列表，第二次注册后会追加，不是「旧句柄」
    List<RecordingScheduledFuture> oldHandles = List.copyOf(taskScheduler.handles());

    scheduler.registerProfile(first);

    assertThat(oldHandles).hasSize(1);
    assertThat(oldHandles.get(0).isCancelled()).isTrue();
    // cancel(false)：正在跑的那一次不打断
    assertThat(oldHandles.get(0).cancelWithInterrupt()).isFalse();
    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("recon#1");
    assertThat(taskScheduler.triggers()).hasSize(2);
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：规则变少时，多出来的旧任务被完全摘掉")
  void registerProfile_dropsRulesNoLongerDeclared() {
    Profile twoRules =
        profile(
            "recon",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", "UTC", "早报"),
                new Profile.ScheduleSpec("0 18 * * *", "UTC", "晚报")));
    Profile oneRule =
        profile("recon", List.of(new Profile.ScheduleSpec("0 9 * * *", "UTC", "只剩早报")));
    AgentScheduler scheduler = scheduler();

    scheduler.registerProfile(twoRules);
    scheduler.registerProfile(oneRule);

    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("recon#1");
    assertThat(scheduler.runNow("recon#2")).isFalse();
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：Agent 名互为前缀时，重新注册一个不误伤另一个")
  void registerProfile_doesNotCancelAgentWhoseNameIsPrefixOfAnother() {
    // 目录名允许含 '#'，故「a」与「a#b」可以同时存在；a#b#1 也以 a# 开头，前缀匹配会把它一起取消
    Profile a = profile("a", List.of(new Profile.ScheduleSpec("0 9 * * *", "UTC", "A 早报")));
    Profile aHashB = profile("a#b", List.of(new Profile.ScheduleSpec("0 18 * * *", "UTC", "B 晚报")));
    AgentScheduler scheduler = scheduler();

    scheduler.registerProfile(a);
    scheduler.registerProfile(aHashB);
    List<RecordingScheduledFuture> before = List.copyOf(taskScheduler.handles());

    scheduler.registerProfile(a);

    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("a#1", "a#b#1");
    // 三个句柄：a 第一次、a#b 那次、a 重注册那次；被取消的只有第一个
    assertThat(before).hasSize(2);
    assertThat(before.get(0).isCancelled()).isTrue();
    assertThat(before.get(1).isCancelled()).isFalse();
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：单条坏 cron 被跳过并指名，其余照常注册")
  void registerProfile_skipsBadRuleAndKeepsOthers() {
    Profile recon =
        profile(
            "recon",
            List.of(
                new Profile.ScheduleSpec("0 9 * *", "UTC", "段数非法"),
                new Profile.ScheduleSpec("0 9 * * *", "Mars/Olympus", "时区非法"),
                new Profile.ScheduleSpec("0 9 * * *", "UTC", "好的")));

    AgentScheduler scheduler = scheduler();
    int registered = scheduler.registerProfile(recon);

    // 失败的两条不占句柄位：序号按配置顺序走，好的那条仍是 #3
    assertThat(registered).isEqualTo(1);
    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("recon#3");
    assertThat(taskScheduler.triggers()).hasSize(1);
    assertThat(((CronTrigger) taskScheduler.triggers().get(0)).getExpression())
        .isEqualTo("0 0 9 * * *");
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：schedule 抛异常——登记行留下但无句柄，runNow 为 false，下一轮成功即自愈")
  void registerProfile_leavesRowButNoHandleWhenSchedulingFails() {
    // 「先登记、后调度」的直接后果：调度器异常时 scheduled_tasks 行已经写进去了。
    // 这是有意的取舍（调度生效前必须先有登记行，否则到点执行会写出一条指向不存在任务的孤儿历史），
    // 故这里把现状钉死，防止有人「顺手」把顺序反过来。
    TaskScheduler failing = mock(TaskScheduler.class);
    when(failing.schedule(any(Runnable.class), any(Trigger.class)))
        .thenThrow(new IllegalStateException("调度器已关闭"));
    AgentScheduler scheduler =
        new AgentScheduler(
            failing, profileRegistry, agentService, sessionService, taskStore, FIXED_CLOCK);
    Profile recon = profile("recon", List.of(new Profile.ScheduleSpec("0 9 * * *", "UTC", "早报")));

    int registered = scheduler.registerProfile(recon);

    assertThat(registered).isZero();
    verify(taskStore).register(any());
    // 没有句柄、也没有登记进内存索引 → 管理台「立即执行」找不到它
    assertThat(scheduler.scheduledTaskHandles()).isEmpty();
    assertThat(scheduler.runNow("recon#1")).isFalse();

    // 自愈：下一次成功注册（例如重启 serve）就把句柄补上，不需要人工清库
    AgentScheduler healthy = scheduler();
    assertThat(healthy.registerProfile(recon)).isEqualTo(1);
    assertThat(healthy.scheduledTaskHandles()).containsOnlyKeys("recon#1");
    assertThat(healthy.runNow("recon#1")).isTrue();
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：schedule 返回 null 仍算注册成功——无句柄但 runNow 可用")
  void registerProfile_countsRuleWithoutHandleWhenSchedulerReturnsNull() {
    // Spring 的 TaskScheduler 契约允许返回 null；返回 null 不等于注册失败，故计入返回值、进内存索引，只是句柄表收不到。
    TaskScheduler nullHandle = mock(TaskScheduler.class);
    when(nullHandle.schedule(any(Runnable.class), any(Trigger.class))).thenReturn(null);
    AgentScheduler scheduler =
        new AgentScheduler(
            nullHandle, profileRegistry, agentService, sessionService, taskStore, FIXED_CLOCK);
    Profile recon = profile("recon", List.of(new Profile.ScheduleSpec("0 9 * * *", "UTC", "早报")));

    assertThat(scheduler.registerProfile(recon)).isEqualTo(1);

    assertThat(scheduler.scheduledTaskHandles()).isEmpty();
    // 与上一条的区别：这条进了 registeredTasks，所以「立即执行」走得通
    assertThat(scheduler.runNow("recon#1")).isTrue();
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：schedules 为空返回 0、不注册任何句柄")
  void registerProfile_withNoSchedulesReturnsZero() {
    AgentScheduler scheduler = scheduler();

    assertThat(scheduler.registerProfile(profile("quiet", List.of()))).isZero();
    assertThat(scheduler.scheduledTaskHandles()).isEmpty();
    assertThat(taskScheduler.triggers()).isEmpty();
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：句柄表是不可变快照，外部改动不影响调度器")
  void scheduledTaskHandles_isUnmodifiableSnapshot() {
    AgentScheduler scheduler = scheduler();
    scheduler.registerProfile(
        profile("recon", List.of(new Profile.ScheduleSpec("0 9 * * *", "UTC", "早报"))));

    assertThatThrownBy(() -> scheduler.scheduledTaskHandles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("recon#1");
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：registerAll 把各 Agent 的新注册数求和")
  void registerAll_sumsPerAgentRegistrations() {
    Profile recon =
        profile(
            "recon",
            List.of(
                new Profile.ScheduleSpec("0 9 * * *", "UTC", "早报"),
                new Profile.ScheduleSpec("0 18 * * *", "UTC", "晚报")));
    Profile quiet = profile("quiet", List.of());
    ProfileRegistry registry = new ProfileRegistry(List.of(recon, quiet));
    AgentScheduler scheduler =
        new AgentScheduler(
            taskScheduler, registry, agentService, sessionService, taskStore, FIXED_CLOCK);

    scheduler.registerAll();

    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("recon#1", "recon#2");
    assertThat(taskScheduler.triggers()).hasSize(2);
  }

  @Test
  @DisplayName("AgentSchedulerRegisterTest：null Profile 显式拒绝")
  void registerProfile_rejectsNull() {
    assertThatThrownBy(() -> scheduler().registerProfile(null))
        .isInstanceOf(NullPointerException.class);
  }

  private AgentScheduler scheduler() {
    return new AgentScheduler(
        taskScheduler, profileRegistry, agentService, sessionService, taskStore, FIXED_CLOCK);
  }

  private static Profile profile(String name, List<Profile.ScheduleSpec> schedules) {
    return new Profile(name, null, null, null, null, null, null, schedules, null, null);
  }

  /**
   * 手写桩：记录每次 {@code schedule} 的 trigger，并回一个可观察的句柄。
   *
   * <p>不用 Mockito：{@code cancel(false)} 与 {@code isCancelled()} 的联动是要断言的行为，手写桩最直观。
   */
  private static final class CapturingTaskScheduler
      implements org.springframework.scheduling.TaskScheduler {

    private final List<Trigger> triggers = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<RecordingScheduledFuture> handles = new ArrayList<>();

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
      RecordingScheduledFuture handle = new RecordingScheduledFuture();
      tasks.add(task);
      triggers.add(trigger);
      handles.add(handle);
      return handle;
    }

    List<Trigger> triggers() {
      return triggers;
    }

    List<RecordingScheduledFuture> handles() {
      return handles;
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

  /** 可观察的假句柄：记住是否被取消、以及是否用了 interrupt 语义。 */
  private static final class RecordingScheduledFuture implements ScheduledFuture<Object> {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean cancelWithInterrupt;

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return other == this ? 0 : 1;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelWithInterrupt = mayInterruptIfRunning;
      return cancelled.compareAndSet(false, true);
    }

    boolean cancelWithInterrupt() {
      return cancelWithInterrupt;
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return cancelled.get();
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  }

  /** 固定时钟 + 「从没跑过」：{@code CronTrigger.nextExecution} 只求下一次。 */
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
