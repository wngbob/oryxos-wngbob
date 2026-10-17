package io.oryxlabs.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.oryxlabs.oryxos.tool.builtin.FileTools;
import io.oryxlabs.oryxos.tool.builtin.HttpTools;
import io.oryxlabs.oryxos.tool.builtin.ShellTools;
import io.oryxlabs.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxlabs.oryxos.tool.notify.NotifyTarget;
import io.oryxlabs.oryxos.tool.notify.NotifyTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真 {@link WhitelistSandbox} 接进四个内置工具之后的接线回归（契约 §5 的 {@code SandboxToolWiringTest} 行）。
 *
 * <p><b>为什么每条都要证明「IO 没有发生」</b>：只断言「抛了异常」是<b>自证</b>——「先做 IO 再校验」的实现同样会抛异常，测试照样绿。第 20 节的 {@code
 * RecordingSandbox} 就是为这件事造的，其 javadoc 已写明这一点。本测试换了个更硬的形态：不去 mock 底层执行器，而是让危险动作
 * <b>留下可观测的痕迹</b>，痕迹不在即证明它没跑——文件没被创建、进程留下的标记文件不存在、请求计数为 0、通知适配器一次没被调。
 *
 * <p><b>不 mock {@code ProcessBuilder}</b>：那需要额外的字节码改写能力，而「让命令留下痕迹」用 JDK 自带的东西就能做到，且证明力等价——
 * 甚至更强，因为它验的是真实的 {@code ShellTools} 代码路径，不是一个被替换掉的假实现。
 */
class SandboxToolWiringTest {

  /** 三个空表 + 一个目录白名单：本测试只关心路径这一类时用。 */
  private static WhitelistSandbox allowingPath(Path allowedRoot) {
    return new WhitelistSandbox(
        new FileSandboxProperties(List.of(allowedRoot.toString())),
        new ShellSandboxProperties(List.of()),
        new HttpSandboxProperties(List.of()));
  }

  private static WhitelistSandbox allowingCommand(String command) {
    return new WhitelistSandbox(
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of(command)),
        new HttpSandboxProperties(List.of()));
  }

  private static WhitelistSandbox allowingDomains(List<String> domains) {
    return new WhitelistSandbox(
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of()),
        new HttpSandboxProperties(domains));
  }

  // ---------- US1：白名单外的文件路径读不到 ----------

  @Test
  @DisplayName("read_file 白名单外的路径被拦，抛的是白名单异常而不是「文件不存在」")
  void readFile_outsideWhitelist_isBlockedBySandboxNotByMissingFile(@TempDir Path tmp) {
    Path allowedRoot = tmp.resolve("allowed");
    Path forbidden = tmp.resolve("forbidden").resolve("secret.txt");

    assertThatThrownBy(
            () ->
                new FileTools(allowingPath(allowedRoot)).readFile(forbidden.toString(), null, null))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessage("路径不在白名单内: " + forbidden);
  }

  @Test
  @DisplayName("write_file 白名单外被拦，文件根本没被创建")
  void writeFile_outsideWhitelist_neverCreatesTheFile(@TempDir Path tmp) {
    Path allowedRoot = tmp.resolve("allowed");
    Path forbidden = tmp.resolve("forbidden").resolve("evil.txt");

    assertThatThrownBy(
            () ->
                new FileTools(allowingPath(allowedRoot))
                    .writeFile(forbidden.toString(), "should never land on disk"))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessage("路径不在白名单内: " + forbidden);

    assertThat(Files.notExists(forbidden)).as("文件根本没被创建").isTrue();
    assertThat(Files.notExists(forbidden.getParent())).as("连父目录都没被创建").isTrue();
  }

  @Test
  @DisplayName("白名单内的路径正常读写，接线没有把可用路径一起拦掉")
  void pathInsideWhitelist_stillWorks(@TempDir Path tmp) throws Exception {
    Path allowedRoot = tmp.resolve("allowed");
    // 白名单内的正向对照要先自己把目录建出来：write_file 的契约是「父目录不存在时不会自动创建」
    Files.createDirectories(allowedRoot);
    Path target = allowedRoot.resolve("notes.txt");
    FileTools tools = new FileTools(allowingPath(allowedRoot));

    assertThat(tools.writeFile(target.toString(), "hello").success()).isTrue();
    assertThat(tools.readFile(target.toString(), null, null).content()).contains("hello");
  }

  // ---------- US2：白名单外的命令一次都没被执行 ----------

  @Test
  @DisplayName("白名单外的命令被拦，进程根本没起（标记文件不存在）")
  void shell_outsideWhitelist_neverStartsTheProcess(@TempDir Path tmp) {
    Path marker = tmp.resolve("marker");

    assertThatThrownBy(() -> new ShellTools(allowingCommand("ls")).shell("touch " + marker, null))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessage("命令不在白名单内: touch");

    assertThat(Files.notExists(marker)).as("进程根本没起，标记文件不存在").isTrue();
  }

  @Test
  @DisplayName("白名单内的命令正常执行")
  void shell_insideWhitelist_stillRuns() {
    assertThat(new ShellTools(allowingCommand("echo")).shell("echo hello", null).content())
        .contains("hello");
  }

  // ---------- US3：白名单外的域名请求根本没发出去 ----------

  @Test
  @DisplayName("http_get 白名单外的域名被拦，请求计数为 0")
  void httpGet_outsideWhitelist_sendsNoRequest() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      String url = server.url("/x").toString();

      // 白名单里放一个与该端点无关的域名：不是「表为空所以全拒」，而是「表非空但没有这一项」
      assertThatThrownBy(
              () -> new HttpTools(allowingDomains(List.of("api.example.com"))).httpGet(url))
          .isInstanceOf(SandboxViolationException.class)
          .hasMessage("域名不在白名单内: " + server.getHostName());

      assertThat(server.getRequestCount()).as("一次请求都没发出去").isZero();
    }
  }

  @Test
  @DisplayName("notify 白名单外的域名被拦，通知适配器一次都没被调")
  void notify_outsideWhitelist_neverCallsTheAdapter() {
    NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
    NotifyTarget target =
        new NotifyTarget("webhook", Map.of("url", "https://evil.example.org/hook"));
    // 注册表本身会被调（把渠道名解析成 URL 不是危险动作，设计如此），故断言对象是 adapter 而不是它
    NotifyTools tools =
        new NotifyTools(allowingDomains(List.of("api.example.com")), adapter, name -> target);

    assertThatThrownBy(() -> tools.notify("hello", null))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessage("域名不在白名单内: evil.example.org");

    verify(adapter, never()).send(any(), any());
  }
}
