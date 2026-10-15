package io.oryxlabs.oryxos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link McpServerConfigLoader} 的守点：路径口径 + 十种失败模式一律不抛异常（contracts/mcp-integration.md §1.3）。
 *
 * <p><b>第一条测试是本节最容易被写漏、也最难被发现的一条</b>：它断言「真读到一个 server」。只测「文件不存在 → 空清单」这类分支时， 多叠一层 {@code .oryxos}
 * 的路径错误会<b>静默通过</b>——文件永远找不到，而「找不到 = 空清单」是合法状态，于是表现为 「MCP 永远连不上、日志里一条错都没有」。正向用例是唯一能抓住它的形式。
 *
 * <p>环境变量通过包级私有的构造注入替身，不碰真实进程环境。
 */
class McpServerConfigLoaderTest {

  @TempDir Path tmp;

  /** 工作区根 = {@code .oryxos/} 目录本身；加载器在它下面解析 {@code mcp_servers.yaml}。 */
  private Path oryxosDir() throws IOException {
    Path dir = tmp.resolve(".oryxos");
    Files.createDirectories(dir);
    return dir;
  }

  private void writeConfig(Path dir, String yaml) throws IOException {
    Files.writeString(dir.resolve("mcp_servers.yaml"), yaml, StandardCharsets.UTF_8);
  }

  private static McpServerConfigLoader loader(Path dir, Map<String, String> env) {
    return new McpServerConfigLoader(dir, env::get);
  }

  @Test
  @DisplayName("真读到一个 server：路径口径是 <工作区根>/mcp_servers.yaml，不叠第二层 .oryxos")
  void readsServerFromWorkspaceRoot() throws IOException {
    Path dir = oryxosDir();
    writeConfig(
        dir,
        """
        servers:
          - name: github-mcp
            transport: stdio
            command: [npx, "-y", "@modelcontextprotocol/server-github"]
            env: { GITHUB_TOKEN: "${GITHUB_TOKEN}" }
        """);

    List<McpServerConfig> configs = loader(dir, Map.of("GITHUB_TOKEN", "tok")).load();

    assertThat(configs).hasSize(1);
    McpServerConfig config = configs.get(0);
    assertThat(config.name()).isEqualTo("github-mcp");
    assertThat(config.transport()).isEqualTo("stdio");
    assertThat(config.command())
        .containsExactly("npx", "-y", "@modelcontextprotocol/server-github");
    assertThat(config.env()).containsEntry("GITHUB_TOKEN", "tok");
  }

  @Test
  @DisplayName("sse 传输：url 读得到，command 可以为空")
  void readsSseServer() throws IOException {
    Path dir = oryxosDir();
    writeConfig(
        dir,
        """
        servers:
          - name: news-hub
            transport: sse
            url: "http://127.0.0.1:9000/sse"
        """);

    List<McpServerConfig> configs = loader(dir, Map.of()).load();

    assertThat(configs).hasSize(1);
    assertThat(configs.get(0).url()).isEqualTo("http://127.0.0.1:9000/sse");
  }

  @Test
  @DisplayName("文件不存在 / 空文件 / 只有注释 / 没有 servers 键 / servers 为空：都返回空清单且不报错")
  void absentOrEmptyConfigYieldsEmptyList() throws IOException {
    Path dir = oryxosDir();
    assertThat(loader(dir, Map.of()).load()).isEmpty();

    writeConfig(dir, "");
    assertThat(loader(dir, Map.of()).load()).isEmpty();

    writeConfig(dir, "# 只有注释\n");
    assertThat(loader(dir, Map.of()).load()).isEmpty();

    writeConfig(dir, "other_key: 1\n");
    assertThat(loader(dir, Map.of()).load()).isEmpty();

    writeConfig(dir, "servers: []\n");
    assertThat(loader(dir, Map.of()).load()).isEmpty();
  }

  @Test
  @DisplayName("非法 transport / stdio 缺 command / sse 缺 url：跳过该 server，不抛异常")
  void invalidEntriesAreSkippedNotThrown() throws IOException {
    Path dir = oryxosDir();
    writeConfig(
        dir,
        """
        servers:
          - name: bad-transport
            transport: websocket
            url: "http://127.0.0.1:9000/sse"
          - name: stdio-no-command
            transport: stdio
          - name: sse-no-url
            transport: sse
        """);

    assertThatCode(() -> assertThat(loader(dir, Map.of()).load()).isEmpty())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("某条坏声明的存在不影响同文件里的好声明")
  void oneBadEntryDoesNotDropTheOthers() throws IOException {
    Path dir = oryxosDir();
    writeConfig(
        dir,
        """
        servers:
          - name: good-one
            transport: stdio
            command: [echo]
          - name: bad-one
            transport: stdio
        """);

    List<McpServerConfig> configs = loader(dir, Map.of()).load();

    assertThat(configs).extracting(McpServerConfig::name).containsExactly("good-one");
  }

  @Test
  @DisplayName("server 名重复：保留先出现的那条")
  void duplicateNameKeepsFirst() throws IOException {
    Path dir = oryxosDir();
    writeConfig(
        dir,
        """
        servers:
          - name: dup
            transport: sse
            url: "http://127.0.0.1:1/sse"
          - name: dup
            transport: sse
            url: "http://127.0.0.1:2/sse"
        """);

    List<McpServerConfig> configs = loader(dir, Map.of()).load();

    assertThat(configs).hasSize(1);
    assertThat(configs.get(0).url()).isEqualTo("http://127.0.0.1:1/sse");
  }

  @Test
  @DisplayName("env 里的 ${VAR} 解析不到：跳过该 server，不猜测、不留下字面量")
  void unresolvedEnvVarSkipsServer() throws IOException {
    Path dir = oryxosDir();
    writeConfig(
        dir,
        """
        servers:
          - name: needs-token
            transport: stdio
            command: [npx]
            env: { GITHUB_TOKEN: "${GITHUB_TOKEN}" }
        """);

    // 环境里没有 GITHUB_TOKEN —— 既不能把 "${GITHUB_TOKEN}" 原样交给子进程，也不能补一个默认值
    assertThat(loader(dir, Map.of()).load()).isEmpty();
  }

  @Test
  @DisplayName("YAML 语法错误：视为空清单，不抛异常")
  void malformedYamlYieldsEmptyList() throws IOException {
    Path dir = oryxosDir();
    writeConfig(dir, "servers: [ unclosed\n  - name: x\n");

    assertThatCode(() -> assertThat(loader(dir, Map.of()).load()).isEmpty())
        .doesNotThrowAnyException();
  }
}
