package io.oryxlabs.oryxos.tool.sandbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 核心阶段唯一的沙箱实现：应用层白名单（技术方案 §6.7，契约 specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md）。
 *
 * <p><b>三类校验</b>：文件路径（{@code file.allowed_paths}，读写共用一份）、可执行文件名（{@code shell.allowed_commands}）、
 * HTTP 域名（{@code http.allowed_domains}，支持 {@code *.} 通配）。校验不通过抛 {@link SandboxViolationException}，
 * 复用 {@code ToolExecutor} 既有的失败审计路径落 {@code tool_invocations}——本类不碰审计。
 *
 * <p><b>这是「劝阻级」防线，不是安全隔离</b>：它防的是模型犯傻与提示注入导致的越权，防不住蓄意绕过（命令拼接、软链接、编码绕过）。 核心阶段 MUST NOT
 * 用它跑完全不可信的代码、MUST NOT 对外做多租户。真要隔离得靠容器 / microVM，那是扩展阶段按信号升级的事—— 升级路径只新增 {@link Sandbox}
 * 的实现类，调用方（五个内置工具）一行不改。
 *
 * <p><b>空表 = 全拒，不是「不校验」</b>：三块白名单都为空时四类动作全部被拒。这不是一条特判分支，而是「存在一个允许项」恒假的结果。
 * 忘配的后果因此是「什么都跑不了」（可见、可查），而不是「什么都放行」（静默、危险）。
 *
 * <p><b>命令条目是「可执行文件名」，两个方向都是坑</b>：配置侧写 {@code /bin/ls} 会与校验时的首 token {@code ls} 永不匹配（静默全拒）； 模型侧写
 * {@code /bin/ls} 也会被拒——{@code ShellTools} 传进来的就是 {@code argv.get(0)}，本类<b>刻意不做 basename 归一</b>，
 * 那会让 {@code /tmp/evil} 改名成 {@code ls} 就通过。两个方向都是 fail-closed，运维看到「配了却不生效」时先查条目形态。
 *
 * <p><b>{@code *.example.com} 覆盖任意层级子域</b>（{@code a.b.example.com} 也命中）——这是 {@code
 * endsWith(".example.com")} 的自然语义，与主流 web 服务器的 {@code ServerAlias} 同款。裸域 {@code example.com}
 * 不命中：通配符表达的是「子域」，不是「同域及其子域」。
 */
public class WhitelistSandbox implements Sandbox {

  /** 路径白名单：构造时逐条标准化为绝对路径，运行期只读。 */
  private final List<Path> allowedRoots;

  /** 命令白名单：构造时 trim 并丢弃空串，运行期只读。 */
  private final Set<String> allowedCommands;

  /** 域名白名单：构造时 trim + 折叠小写并丢弃空串，运行期只读。 */
  private final List<String> allowedDomainPatterns;

  /**
   * @param fileProps 文件路径白名单（目录前缀集合）
   * @param shellProps 可执行文件名白名单
   * @param httpProps HTTP 域名白名单（精确名或 {@code *.} 通配）
   */
  public WhitelistSandbox(
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    this.allowedRoots = normalizeRoots(fileProps.allowedPaths());
    this.allowedCommands = normalizeCommands(shellProps.allowedCommands());
    this.allowedDomainPatterns = normalizeDomains(httpProps.allowedDomains());
  }

  /**
   * 逐条转成绝对路径。
   *
   * <p><b>必须转绝对</b>：默认配置值是 {@code .}（相对路径），而校验目标会被转成绝对路径——相对与绝对比对 {@code startsWith} 恒为 false，
   * 不转会让随包默认配置<b>全拒</b>，开箱即不可用。
   *
   * <p><b>空条目丢弃</b>：空串会归一成当前工作目录，等价于「放行整个工作目录」——一个手滑多打的逗号就能把白名单悄悄放大。
   */
  private static List<Path> normalizeRoots(List<String> raw) {
    List<Path> roots = new ArrayList<>();
    for (String entry : raw) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      roots.add(Path.of(entry.trim()).normalize().toAbsolutePath());
    }
    return List.copyOf(roots);
  }

  /**
   * 逐条 trim 后丢弃空串。
   *
   * <p><b>不折叠大小写</b>：命令名是文件名，{@code ls} 与 {@code LS} 在 Linux 上是两个不同的可执行文件。折叠等于凭空放行一个白名单里 没有的程序。
   */
  private static Set<String> normalizeCommands(List<String> raw) {
    Set<String> commands = new LinkedHashSet<>();
    for (String entry : raw) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      commands.add(entry.trim());
    }
    return Set.copyOf(commands);
  }

  /**
   * 逐条 trim 并折叠为小写，丢弃空串。
   *
   * <p><b>这里折叠大小写与命令那边相反，是有意的</b>：域名本身大小写不敏感（{@code API.Example.COM} 与 {@code api.example.com}
   * 是同一台主机），不折叠会把一个合法请求拒掉。
   */
  private static List<String> normalizeDomains(List<String> raw) {
    List<String> domains = new ArrayList<>();
    for (String entry : raw) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      domains.add(entry.trim().toLowerCase(Locale.ROOT));
    }
    return List.copyOf(domains);
  }

  @Override
  public void enforce(SandboxAction action) {
    switch (action.type()) {
      case FILE_READ:
      case FILE_WRITE:
        // 读写共用一份白名单（FR-002）。两类分开建模是留给未来按读写分权的接缝，不是本节的行为差异
        checkFilePath(action.target());
        break;
      case SHELL_COMMAND:
        checkShellCommand(action.target());
        break;
      case HTTP_REQUEST:
        checkHttpUrl(action.target());
        break;
      default:
        // 将来给 ActionType 加了第五个值却忘了加路由时，在这里当场炸——静默放行会让新动作类型无声地绕过沙箱
        throw new IllegalStateException("未接入沙箱校验的动作类型: " + action.type());
    }
  }

  /**
   * 路径校验：标准化后按<b>路径组件</b>前缀比对。
   *
   * <p>用 {@link Path#startsWith} 而不是字符串 {@code startsWith}：后者会把 {@code /workspace-evil/x} 判成 {@code
   * /workspace} 的子路径——字符串上确实以它开头，路径上却是另一棵子树。
   *
   * @param rawPath 模型给的原始路径；消息里带的是它（不是标准化后的），便于运维对号
   * @throws SandboxViolationException 不在任何白名单根目录下
   */
  private void checkFilePath(String rawPath) {
    Path target = Path.of(rawPath).normalize().toAbsolutePath();
    for (Path root : allowedRoots) {
      if (target.startsWith(root)) {
        return;
      }
    }
    throw new SandboxViolationException("路径不在白名单内: " + rawPath);
  }

  /**
   * 命令校验：只比对 {@code trim()} 后的首 token。
   *
   * <p>与「argv 直传、不经 Shell 解释」的口径一致（FR-003）：{@code ShellTools} 把命令串切成 argv 直接交给 {@code
   * ProcessBuilder}，所以没有 {@code &&} / {@code ;} / {@code $()} 这类解释层，可执行文件就钉在第一位。
   *
   * <p><b>先 trim 再切</b>：不 trim 的话 {@code " rm -rf /"} 的首 token 是空串，而空串与「白名单里的空条目」可能正好配上——
   * 前导空白就成了绕过手段。空条目在构造时已丢弃，这里再对空 token 直接拒，两道口都堵上。
   *
   * @param command 模型给的整条命令
   * @throws SandboxViolationException 首 token 为空或不在白名单内；消息带首 token（判定对象就是它）
   */
  private void checkShellCommand(String command) {
    String trimmed = command == null ? "" : command.trim();
    String firstToken = trimmed.split("\\s+")[0];
    if (firstToken.isEmpty() || !allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
  }

  /**
   * 域名校验：解析 host 后比对，{@code *.} 通配按点号边界匹配。
   *
   * <p><b>通配必须落在点号边界上</b>：裸用 {@code endsWith("example.com")} 会让 {@code evil-example.com} 通过—— 它确实以
   * {@code example.com} 结尾，却是完全不相干的一台主机。{@code pattern.substring(1)} 保留了那个点号，这就是边界。
   *
   * <p><b>解析不出 host 一律拒，且不得抛 NPE</b>：{@code file:} / {@code mailto:} 这类 URI 的 host 是 null， 拿 null
   * 去比对的结果取决于写法，赌不起；而让 NPE 冒到运维面前，他读到的是一个与白名单毫无关系的失败。
   *
   * <p><b>关于 {@code @SuppressFBWarnings}</b>：FindSecBugs 的 IMPROPER_UNICODE
   * 见到「不可信输入被大小写折叠后用于安全判定」就报，本条是 误报，理由三条：① 折叠用的是 {@code Locale.ROOT}（该规则要求的正是这个，不是无参的 {@code
   * toLowerCase()}）—— 判定结果与运行机器的 默认区域设置无关，土耳其语环境的 {@code I} 之类的坑不存在；②
   * 折叠后<b>整串</b>按点号边界比对，不存在「部分匹配」这种中间态， 而 Unicode 折叠 至多改变长度或码点，不可能凭空造出结尾的 {@code ".example.com"}；③
   * 真正决定放行的是配置侧白名单， 折叠只影响「同一台主机的不同大小写写法算不算同一个」—— 折叠失败的方向是拒（fail-closed），不是放行。就地豁免并留下理由，比在全局
   * excludeFilterFile 里开口子更可审计。
   *
   * @param rawUrl 模型给的完整 URL
   * @throws SandboxViolationException 解析失败、无 host、或 host 不在白名单内
   */
  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "host 折叠用 Locale.ROOT（该规则要求的口径）、折叠后整串按点号边界比对、失配即拒（fail-closed），属 FindSecBugs 对「不可信输入参与大小写比对」的误报")
  private void checkHttpUrl(String rawUrl) {
    String host;
    try {
      host = URI.create(rawUrl).getHost();
    } catch (IllegalArgumentException e) {
      throw new SandboxViolationException("域名不在白名单内: " + rawUrl);
    }
    if (host == null) {
      throw new SandboxViolationException("域名不在白名单内: " + rawUrl);
    }
    String lowerHost = host.toLowerCase(Locale.ROOT);
    for (String pattern : allowedDomainPatterns) {
      if (pattern.startsWith("*.")) {
        if (lowerHost.endsWith(pattern.substring(1))) {
          return;
        }
      } else if (lowerHost.equals(pattern)) {
        return;
      }
    }
    throw new SandboxViolationException("域名不在白名单内: " + host);
  }
}
