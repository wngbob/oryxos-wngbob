package io.oryxlabs.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 三类校验的成对用例与绕过场景（契约 specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md §2）。
 *
 * <p><b>纯单测、不起 Spring 上下文</b>：白名单的语义与「谁把它装配进容器」是两件事，混在一起验会让一条校验规则写错时表现为「容器起不来」， 排查成本陡增。装配那一层由
 * {@code ToolAssemblyTest} 单独守。
 *
 * <p><b>为什么每条绕过场景都要有</b>：三类校验各自有一个「看起来对了但能被绕过」的写法——裸字符串前缀比对（{@code /workspace-evil}）、 未 trim 的首
 * token（前导空白）、裸 {@code endsWith("example.com")}（{@code evil-example.com}）。这些写法在正向用例上全绿，
 * 所以正向用例单独存在等于没有测试。
 */
class WhitelistSandboxTest {

  /** 三类白名单一次给全，用例只关心自己那一类，另外两类留空。 */
  private static WhitelistSandbox sandbox(
      List<String> paths, List<String> commands, List<String> domains) {
    return new WhitelistSandbox(
        new FileSandboxProperties(paths),
        new ShellSandboxProperties(commands),
        new HttpSandboxProperties(domains));
  }

  private static WhitelistSandbox pathsOnly(List<String> paths) {
    return sandbox(paths, List.of(), List.of());
  }

  private static WhitelistSandbox commandsOnly(List<String> commands) {
    return sandbox(List.of(), commands, List.of());
  }

  private static WhitelistSandbox domainsOnly(List<String> domains) {
    return sandbox(List.of(), List.of(), domains);
  }

  private static void assertAllowed(WhitelistSandbox sandbox, ActionType type, String target) {
    assertThatCode(() -> sandbox.enforce(new SandboxAction(type, target)))
        .as("%s 应放行 %s", type, target)
        .doesNotThrowAnyException();
  }

  private static void assertRejected(WhitelistSandbox sandbox, ActionType type, String target) {
    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(type, target)))
        .as("%s 应拒绝 %s", type, target)
        .isInstanceOf(SandboxViolationException.class);
  }

  @Nested
  @DisplayName("路径校验（file.allowed_paths）")
  class FilePath {

    @Test
    @DisplayName("白名单内的文件放行")
    void pathInsideWhitelist_isAllowed() {
      assertAllowed(pathsOnly(List.of("/workspace")), ActionType.FILE_READ, "/workspace/notes.txt");
    }

    @Test
    @DisplayName("白名单是目录前缀，子目录里的文件也放行")
    void pathInSubdirectory_isAllowed() {
      assertAllowed(pathsOnly(List.of("/workspace")), ActionType.FILE_READ, "/workspace/a/b.txt");
    }

    @Test
    @DisplayName("白名单目录自身放行")
    void pathEqualToWhitelistRoot_isAllowed() {
      assertAllowed(pathsOnly(List.of("/workspace")), ActionType.FILE_READ, "/workspace");
    }

    @Test
    @DisplayName("白名单外的路径拒绝")
    void pathOutsideWhitelist_isRejected() {
      assertRejected(pathsOnly(List.of("/workspace")), ActionType.FILE_READ, "/etc/passwd");
    }

    @Test
    @DisplayName("相对路径穿越必须被拦")
    void relativePathTraversal_isBlocked() {
      assertRejected(
          pathsOnly(List.of("/workspace")),
          ActionType.FILE_READ,
          "/workspace/../../outside/secret.txt");
    }

    @Test
    @DisplayName("前缀必须落在路径组件边界上，/workspace-evil 不是 /workspace 的子路径")
    void siblingDirectoryWithSharedStringPrefix_isRejected() {
      assertRejected(pathsOnly(List.of("/workspace")), ActionType.FILE_READ, "/workspace-evil/x");
    }

    @Test
    @DisplayName("写文件与读文件走同一份白名单")
    void fileWrite_usesSameWhitelistAsRead() {
      assertAllowed(pathsOnly(List.of("/workspace")), ActionType.FILE_WRITE, "/workspace/out.txt");
      assertRejected(pathsOnly(List.of("/workspace")), ActionType.FILE_WRITE, "/etc/shadow");
    }

    @Test
    @DisplayName("拒绝消息带原始输入路径，运维能对号")
    void rejectedMessage_carriesOriginalInputPath() {
      assertThatThrownBy(
              () ->
                  pathsOnly(List.of("/workspace"))
                      .enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")))
          .isInstanceOf(SandboxViolationException.class)
          .hasMessage("路径不在白名单内: /etc/passwd");
    }

    @Test
    @DisplayName("相对白名单条目（默认值 .）经标准化后能放行工作目录下的文件")
    void relativeWhitelistEntry_isNormalizedToAbsolute() {
      String cwd = System.getProperty("user.dir");
      assertAllowed(pathsOnly(List.of(".")), ActionType.FILE_READ, cwd + "/pom.xml");
      // 同时证明 `.` 没有变成「放行一切」：随包默认配置若不转绝对路径就是全拒，转了也只该放行工作目录这一棵子树
      assertRejected(pathsOnly(List.of(".")), ActionType.FILE_READ, "/etc/passwd");
    }

    @Test
    @DisplayName("空 / 纯空白的白名单条目被丢弃，不得变成「匹配一切」")
    void blankWhitelistEntry_isDropped() {
      assertRejected(pathsOnly(List.of("", "   ")), ActionType.FILE_READ, "/etc/passwd");
    }
  }

  @Nested
  @DisplayName("可执行文件校验（shell.allowed_commands）")
  class ShellCommand {

    @Test
    @DisplayName("白名单内的命令放行，只比首 token")
    void commandInWhitelist_isAllowed() {
      assertAllowed(commandsOnly(List.of("ls")), ActionType.SHELL_COMMAND, "ls -la");
    }

    @Test
    @DisplayName("前导空白不是绕过手段")
    void leadingWhitespace_isNotABypass() {
      assertAllowed(commandsOnly(List.of("ls")), ActionType.SHELL_COMMAND, "  ls -la");
    }

    @Test
    @DisplayName("白名单外的命令拒绝，消息带首 token")
    void commandOutsideWhitelist_isRejected() {
      assertThatThrownBy(
              () ->
                  commandsOnly(List.of("ls"))
                      .enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /")))
          .isInstanceOf(SandboxViolationException.class)
          .hasMessage("命令不在白名单内: rm");
    }

    @Test
    @DisplayName("精确比对不折叠大小写：命令名是文件名")
    void uppercaseCommand_isRejected() {
      assertRejected(commandsOnly(List.of("ls")), ActionType.SHELL_COMMAND, "LS");
    }

    @Test
    @DisplayName("全空白命令切不出首 token 即拒")
    void blankCommand_isRejected() {
      assertRejected(commandsOnly(List.of("ls")), ActionType.SHELL_COMMAND, "   ");
    }

    @Test
    @DisplayName("空白命令即使配上空条目白名单也仍拒（空条目被丢弃）")
    void blankCommand_withBlankEntryInWhitelist_isStillRejected() {
      assertRejected(commandsOnly(List.of("")), ActionType.SHELL_COMMAND, "   ");
    }

    @Test
    @DisplayName("空命令同样拒")
    void emptyCommand_isRejected() {
      assertRejected(commandsOnly(List.of("ls")), ActionType.SHELL_COMMAND, "");
    }
  }

  @Nested
  @DisplayName("域名校验（http.allowed_domains）")
  class HttpDomain {

    @Test
    @DisplayName("通配符命中子域")
    void wildcardPattern_matchesSubdomain() {
      assertAllowed(
          domainsOnly(List.of("*.example.com")),
          ActionType.HTTP_REQUEST,
          "https://api.example.com/x");
    }

    @Test
    @DisplayName("通配符域名_不能被形似域名绕过")
    void wildcardDomain_isNotBypassedByLookalikeDomain() {
      assertRejected(
          domainsOnly(List.of("*.example.com")),
          ActionType.HTTP_REQUEST,
          "https://evil-example.com/x");
    }

    @Test
    @DisplayName("通配符只覆盖子域，裸域不命中")
    void wildcardPattern_doesNotMatchBareDomain() {
      assertRejected(
          domainsOnly(List.of("*.example.com")), ActionType.HTTP_REQUEST, "https://example.com/x");
    }

    @Test
    @DisplayName("精确域名按相等匹配")
    void exactDomain_matchesOnlyItself() {
      assertAllowed(
          domainsOnly(List.of("api.example.com")),
          ActionType.HTTP_REQUEST,
          "https://api.example.com/x");
      assertRejected(
          domainsOnly(List.of("api.example.com")),
          ActionType.HTTP_REQUEST,
          "https://other.example.com/x");
    }

    @Test
    @DisplayName("host 比对不区分大小写")
    void hostComparison_isCaseInsensitive() {
      assertAllowed(
          domainsOnly(List.of("api.example.com")),
          ActionType.HTTP_REQUEST,
          "https://API.Example.COM/x");
    }

    @Test
    @DisplayName("无 host 的 URL 被拒，抛的是白名单异常而不是 NPE")
    void urlWithoutHost_isRejectedNotNpe() {
      assertRejected(
          domainsOnly(List.of("*.example.com")), ActionType.HTTP_REQUEST, "file:/etc/passwd");
    }

    @Test
    @DisplayName("解析不出 host 的输入被拒，抛的是白名单异常而不是 NPE")
    void unparseableUrl_isRejectedNotNpe() {
      assertRejected(domainsOnly(List.of("*.example.com")), ActionType.HTTP_REQUEST, "不是 URL");
    }

    @Test
    @DisplayName("拒绝消息带 host")
    void rejectedMessage_carriesHost() {
      assertThatThrownBy(
              () ->
                  domainsOnly(List.of("api.example.com"))
                      .enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://evil.com/x")))
          .isInstanceOf(SandboxViolationException.class)
          .hasMessage("域名不在白名单内: evil.com");
    }

    @Test
    @DisplayName("空 / 纯空白的域名条目被丢弃")
    void blankDomainEntry_isDropped() {
      assertRejected(domainsOnly(List.of("", "  ")), ActionType.HTTP_REQUEST, "https://evil.com/x");
    }
  }

  @Nested
  @DisplayName("空配置 = 全拒（不是「不校验」）")
  class EmptyWhitelist {

    @Test
    @DisplayName("三块白名单全空时，四类动作逐类全部拒绝")
    void allEmpty_everyActionTypeIsRejected() {
      WhitelistSandbox sandbox = sandbox(List.of(), List.of(), List.of());

      assertRejected(sandbox, ActionType.FILE_READ, "/workspace/notes.txt");
      assertRejected(sandbox, ActionType.FILE_WRITE, "/workspace/out.txt");
      assertRejected(sandbox, ActionType.SHELL_COMMAND, "ls");
      assertRejected(sandbox, ActionType.HTTP_REQUEST, "https://api.example.com/x");
    }

    @Test
    @DisplayName("白名单非空但都不匹配，同样拒绝（不是「表非空就放行」）")
    void nonEmptyButUnmatched_isStillRejected() {
      assertRejected(pathsOnly(List.of("/workspace")), ActionType.FILE_READ, "/tmp/x");
      assertRejected(commandsOnly(List.of("ls")), ActionType.SHELL_COMMAND, "cat");
      assertRejected(
          domainsOnly(List.of("api.example.com")), ActionType.HTTP_REQUEST, "https://evil.com/x");
    }

    @Test
    @DisplayName("配置块缺失（null）归一化为空表，行为与空表完全一致")
    void nullProperties_behaveExactlyLikeEmptyLists() {
      WhitelistSandbox sandbox =
          new WhitelistSandbox(
              new FileSandboxProperties(null),
              new ShellSandboxProperties(null),
              new HttpSandboxProperties(null));

      assertRejected(sandbox, ActionType.FILE_READ, "/workspace/notes.txt");
      assertRejected(sandbox, ActionType.FILE_WRITE, "/workspace/out.txt");
      assertRejected(sandbox, ActionType.SHELL_COMMAND, "ls");
      assertRejected(sandbox, ActionType.HTTP_REQUEST, "https://api.example.com/x");
    }
  }

  @Nested
  @DisplayName("对外只有一个入口（FR-005）")
  class SingleEntryPoint {

    @Test
    @DisplayName("除 enforce 外没有别的 public 方法")
    void publicSurface_isOnlyEnforce() {
      Set<String> publicMethods =
          Arrays.stream(WhitelistSandbox.class.getMethods())
              .filter(m -> m.getDeclaringClass() != Object.class)
              .map(Method::getName)
              .collect(Collectors.toSet());

      assertThat(publicMethods).containsExactly("enforce");
    }

    @Test
    @DisplayName("三个校验方法是 private，接口不被第一档实现带偏")
    void checkMethods_arePrivate() {
      List<Method> declared =
          Arrays.stream(WhitelistSandbox.class.getDeclaredMethods())
              .filter(m -> !m.isSynthetic())
              .toList();

      assertThat(declared)
          .filteredOn(m -> !"enforce".equals(m.getName()))
          .allMatch(m -> Modifier.isPrivate(m.getModifiers()), "非 enforce 的声明方法必须都是 private");

      Set<String> names = declared.stream().map(Method::getName).collect(Collectors.toSet());
      assertThat(names).contains("checkFilePath", "checkShellCommand", "checkHttpUrl");
    }
  }
}
