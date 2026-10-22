package io.oryxlabs.oryxos.core.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实时监听 {@code agents/}：丢一个目录进来就等于上线一个 Agent，免重启（第 30 节 FR-003）。
 *
 * <p><b>它是一个 daemon {@code Thread}，不是线程池</b>：目录事件的串行处理本来就用不上并发，引入 {@code ExecutorService} / {@code
 * CompletableFuture} 只会把「一次事件处理挂住就占用一个池位」这类问题带进来，也违反章程原则 8 的同步模型。 请求链路完全不感知它—— 它只改运行时索引，不发消息、不碰模型。
 *
 * <p><b>为什么要重试（这一节最容易漏的一处）</b>：{@code cp -a} 先建目录、再往里落文件，{@code ENTRY_CREATE} 到达时 {@code AGENT.md}
 * 往往还没写完；而内层文件的事件打在子目录上，{@code agents/} 这一层<b>不会再收到第二个事件</b>。首次登记失败后若就此放手， 那个 Agent
 * 会永久漏注册——「文件明明在，列表里就是没有」。故失败的目录进有界重试队列（{@link #MAX_ATTEMPTS} × {@link
 * #RETRY_INTERVAL_MILLIS}），由主循环的 {@code poll} 驱动，<b>不为此另开线程或定时器</b>。
 *
 * <p><b>已知边界</b>：重试耗尽后放弃的那个目录，要等下一次重启扫描或新的目录事件才会再试——不会自己恢复。
 */
public class WorkspaceWatcher {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceWatcher.class);

  /** 主循环每轮最长阻塞时间：既是事件等待，也是重试队列的节拍。 */
  private static final long POLL_MILLIS = 200;

  /** 单个目录的登记尝试上限（首次失败后再试 10 次）。 */
  private static final int MAX_ATTEMPTS = 10;

  /** 两次尝试之间的最小间隔。 */
  private static final long RETRY_INTERVAL_MILLIS = 300;

  /** 线程名：排障时 {@code jstack} 里一眼能认出这条守护线程。 */
  private static final String THREAD_NAME = "oryxos-watcher";

  private static final String AGENT_FILE = "AGENT.md";

  /** 生命周期编排：目录事件的唯一落点（与 API 创建走同一个 {@code register}）。 */
  private final AgentLifecycleService lifecycleService;

  /** 被监听的目录（{@code <oryxos>/agents}）。 */
  private final Path agentsDir;

  /** 等重试的目录：按目录名去重，值里记已试次数与下次到点时间。只在工作线程里读写。 */
  private final Map<String, PendingRetry> pending = new LinkedHashMap<>();

  private WatchService watchService;
  private Thread thread;

  /**
   * @param lifecycleService 生命周期编排（本类只调 {@code register} / {@code unregister}）
   * @param agentsDir 被监听的 Agent 目录（即 {@code <oryxos>/agents}）
   */
  public WorkspaceWatcher(AgentLifecycleService lifecycleService, Path agentsDir) {
    this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
    this.agentsDir = Objects.requireNonNull(agentsDir, "agentsDir");
  }

  /**
   * 启动监听：先全量扫一遍已有目录，再造 {@code WatchService} 与守护线程。
   *
   * <p><b>幂等</b>：重复调用只返回，不重复建线程、不重复注册事件（{@code serve} 里被调用两次不该出现两条监听线程）。
   *
   * <p><b>目录不存在时先建出来</b>：{@code oryxos init} 会建，但有人可能只拷了 {@code .oryxos} 的一部分；监听一个不存在的目录会直接抛 {@code
   * NoSuchFileException}，让整个 {@code serve} 起不来——那与「实时监听是锦上添花」的定位不符。
   *
   * <p><b>为什么显式建线程而不是线程池</b>（P3C {@code AvoidManuallyCreateThreadRule} 的就地豁免）：本仓章程原则 8 与 H4
   * 不变量⑤明令核心阶段 **不引入自建线程池**（同步阻塞模型 + 虚拟线程）。这里需要的是**恰好一条**常驻的、可被 {@link #stop()} 精确中断的守护线程——{@code
   * Executors} 会给出一条池化的生命周期语义（{@code shutdownNow} 打断谁、何时回收都由框架决定），既与章程冲突，也让「停掉就是停掉」变得不可判。
   *
   * @throws UncheckedIOException 无法监听该目录
   */
  @SuppressWarnings("PMD.AvoidManuallyCreateThreadRule")
  public synchronized void start() {
    if (thread != null && thread.isAlive()) {
      return;
    }
    try {
      Files.createDirectories(agentsDir);
      scanExisting();
      WatchService service = FileSystems.getDefault().newWatchService();
      agentsDir.register(
          service,
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_DELETE);
      this.watchService = service;
      this.pending.clear();
      Thread worker = new Thread(() -> loop(service), THREAD_NAME);
      // daemon：它不承担任何持久化职责，进程该退出时不该被它拖住
      worker.setDaemon(true);
      this.thread = worker;
      worker.start();
    } catch (IOException e) {
      throw new UncheckedIOException("无法监听 Agent 目录：" + agentsDir, e);
    }
  }

  /**
   * 停止监听：关闭 {@code WatchService} 并中断线程（{@code serve} 关闭与测试收尾用）。
   *
   * <p>幂等：未启动或已停止时直接返回。
   */
  public synchronized void stop() {
    if (watchService != null) {
      try {
        // close() 会让阻塞中的 poll 立刻抛 ClosedWatchServiceException，循环据此退出
        watchService.close();
      } catch (IOException e) {
        log.debug("关闭 WatchService 失败（不影响退出）：{}", sanitize(e.getMessage()));
      }
      watchService = null;
    }
    if (thread != null) {
      thread.interrupt();
      thread = null;
    }
  }

  /** 启动扫描：{@code agents/} 下每个直接子目录都试登记一次，坏目录只告警。 */
  private void scanExisting() {
    try (Stream<Path> children = Files.list(agentsDir)) {
      children.filter(Files::isDirectory).sorted().forEach(this::registerQuietly);
    } catch (IOException e) {
      // 扫不动不是致命错：监听线程起来后，目录事件照样能把后来的目录登记上
      log.warn("启动扫描 Agent 目录失败：{}", sanitize(e.getMessage()));
    }
  }

  /** 单目录登记：失败进重试队列；启动扫描与事件处理共用。 */
  private void registerQuietly(Path dir) {
    String name = nameOf(dir);
    try {
      lifecycleService.register(dir);
      log.info("已登记 Agent：{}", sanitize(name));
    } catch (RuntimeException e) {
      scheduleRetry(name, dir, e);
    }
  }

  /** 主循环：事件串行处理 + 到期重试，两条路径都不新开线程。 */
  private void loop(WatchService service) {
    while (!Thread.currentThread().isInterrupted()) {
      WatchKey key;
      try {
        key = service.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException e) {
        return;
      }
      if (key != null) {
        for (WatchEvent<?> event : key.pollEvents()) {
          handleEvent(event);
        }
        if (!key.reset()) {
          // 被监听的目录本身没了：没有可监听的锚点，退出比空转好
          log.warn("Agent 目录已不可监听，监听线程退出：{}", sanitize(agentsDir.toString()));
          return;
        }
      }
      retryDue();
    }
  }

  /** 单个事件的处理：任何异常只告警，线程继续（一个坏目录不该让整个监听停摆）。 */
  private void handleEvent(WatchEvent<?> event) {
    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
      return;
    }
    Object context = event.context();
    if (!(context instanceof Path relative)) {
      return;
    }
    Path child = agentsDir.resolve(relative);
    String name = nameOf(child);
    try {
      if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
        pending.remove(name);
        lifecycleService.unregister(name);
        log.info("Agent 目录已移除，已注销：{}", sanitize(name));
      } else if (Files.isDirectory(child)) {
        registerQuietly(child);
      }
    } catch (RuntimeException e) {
      log.warn("处理 Agent 目录事件失败：{}（{}）", sanitize(name), sanitize(e.getMessage()));
    }
  }

  /** 首次失败：记进待重试表（同名目录只留一份，`cp -a` 的连续事件不该堆出十次并行重试）。 */
  private void scheduleRetry(String name, Path dir, RuntimeException cause) {
    PendingRetry retry = pending.get(name);
    if (retry == null) {
      retry = new PendingRetry(dir);
      pending.put(name, retry);
    }
    retry.attempts = 1;
    retry.nextAttemptAtNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RETRY_INTERVAL_MILLIS);
    if (!hasAgentFile(dir)) {
      // 拷贝竞态的典型形态：目录先到、文件还没落。这条 DEBUG 是「正在等文件」的唯一线索
      log.debug("Agent 目录里还没有 {}，稍后重试：{}", AGENT_FILE, sanitize(name));
    } else {
      log.warn("Agent 登记失败，稍后重试：{}（{}）", sanitize(name), sanitize(cause.getMessage()));
    }
  }

  /** 处理到期的重试：成功即移除，耗尽则 WARN 后放弃。 */
  private void retryDue() {
    if (pending.isEmpty()) {
      return;
    }
    long now = System.nanoTime();
    Iterator<Map.Entry<String, PendingRetry>> iterator = pending.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, PendingRetry> entry = iterator.next();
      PendingRetry retry = entry.getValue();
      if (now < retry.nextAttemptAtNanos) {
        continue;
      }
      try {
        lifecycleService.register(retry.dir);
        iterator.remove();
        log.info("重试成功，已登记 Agent：{}", sanitize(entry.getKey()));
      } catch (RuntimeException e) {
        retry.attempts++;
        if (retry.attempts > MAX_ATTEMPTS) {
          iterator.remove();
          log.warn(
              "Agent 登记重试 {} 次仍失败，放弃（下次重启或新事件再试）：{}（{}）",
              MAX_ATTEMPTS,
              sanitize(entry.getKey()),
              sanitize(e.getMessage()));
        } else {
          retry.nextAttemptAtNanos = now + TimeUnit.MILLISECONDS.toNanos(RETRY_INTERVAL_MILLIS);
        }
      }
    }
  }

  private static boolean hasAgentFile(Path dir) {
    return Files.isRegularFile(dir.resolve(AGENT_FILE));
  }

  /**
   * 日志里的 Agent 名与异常消息都来自文件系统（不可信输入），换行必须清掉——否则一条构造过的目录名能把一行日志劈成两条。
   *
   * @param text 原始文本，可空
   * @return 不含 CR / LF 的文本
   */
  private static String sanitize(String text) {
    return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
  }

  /** 目录名；根路径（无文件名）时退回整串，避免 NPE。 */
  private static String nameOf(Path dir) {
    Path fileName = dir.getFileName();
    return fileName == null ? dir.toString() : fileName.toString();
  }

  /** 一个等待重试的目录：同一目录名只保留一份。 */
  private static final class PendingRetry {

    private final Path dir;
    private int attempts;
    private long nextAttemptAtNanos;

    private PendingRetry(Path dir) {
      this.dir = dir;
    }
  }
}
