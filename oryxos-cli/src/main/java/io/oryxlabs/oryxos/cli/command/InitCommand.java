package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * {@code oryxos init}：在当前目录建出完整工作区（FR-001、contracts/cli-surface.md §2.1）。
 *
 * <p><b>唯一不需要「先有工作区」的命令</b>——它就是造工作区的那条。其余十一条命令都先经 {@link Workspace#requireExists()} 把「你没在工作区里」拦下来。
 *
 * <p><b>幂等的落地方式是「先问再做」</b>：每个目录与文件先判存在，存在就只记一笔「已有」，一个字节都不写。
 * 不采用「先写再回滚」或「覆盖后比对」——那两种做法都会在失败时留下被改坏的文件，而 {@code init} 最常见的使用场景恰恰是「在已经跑了一阵的工作区里再跑一次」。
 *
 * <p><b>数据库是唯一的例外（2026-10-20 用户批准）</b>：目录与文件一个字节都不覆盖，但已存在表缺的列会被 {@link
 * WorkspaceDb#applySchemaDrift()} 补上——{@code schema.sql} 的 {@code CREATE TABLE IF NOT EXISTS}
 * 进不了旧库，不补列会让运行期每次审计写入都失败。补列只增不删、只补不改，类型不一致只打出来给人看。
 *
 * <p><b>不生成任何默认 Agent</b>（spec 仲裁 14）：{@code agents/} 建出来是空目录，{@code init} 之后直接 {@code chat} 会提示先
 * {@code profile create}。预置一个「demo」Agent 看着友好，实则让用户以为自己已经有了什么。
 *
 * <p><b>不建 {@code notify_channels.yaml} / {@code sandbox.yaml}</b>（spec 仲裁 7）：通知渠道归 SQLite 全局注册表 （第
 * 19 节）、沙箱白名单归第 24 节，各自契约落地时再补。上游 {@code contracts/cli.md} 列了这两个模板，是旧口径。
 */
@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = "在当前目录创建工作区 .oryxos/（幂等：已存在的目录与文件一律不覆盖）")
public final class InitCommand implements Runnable {

  /** 工作区子目录，顺序即输出顺序。 */
  private static final List<String> DIRECTORIES =
      List.of("agents", "skills", "memory", "sessions", "logs", "output");

  /**
   * 长期记忆文件模板：两个一级分区标题（contracts/agent-directory.md §6）。
   *
   * <p>只给分区标题、不写示例条目：核心区永不截断地全量注入 prompt，凭空塞一条示例记忆等于凭空给 Agent 加一句 它没说过的话。
   */
  private static final String MEMORY_MD =
      """
      ## 核心记忆

      ## 归档记忆
      """;

  /**
   * MCP server 配置模板（contracts/agent-directory.md §4）。
   *
   * <p>键名逐字取自契约；示例全部注释掉，{@code servers} 留空表——本节（第 18 节）只建模板，连接归第 20 节。 预置一个连不上的 server 会让第 20
   * 节的启动日志每次都多一条失败。
   */
  private static final String MCP_SERVERS_YAML =
      """
      # MCP server 配置模板（契约 specs/000-runtime-kernel/contracts/agent-directory.md §4）
      #
      # 每条声明一个要连接的 MCP server，Agent 在 AGENT.md 的 mcp_servers 字段按 name 引用。
      # 凭证 MUST 用 ${ENV_VAR} 占位，禁止明文。
      #
      # servers:
      #   - name: github-mcp          # 注册名，AGENT.md 按名引用
      #     transport: stdio          # stdio | sse
      #     command: [npx, "-y", "@modelcontextprotocol/server-github"]   # stdio: argv 直传
      #     env: { GITHUB_TOKEN: "${GITHUB_TOKEN}" }                      # 凭证占位
      #   - name: news-hub
      #     transport: sse
      #     url: "http://127.0.0.1:9000/sse"                              # sse 用 url
      #
      # 本节只建模板，不连接任何 server —— 连接与注册归第 20 节。
      servers: []
      """;

  /** 项目级 Agent 行为说明模板：所有 Agent 共享的背景与约定。 */
  private static final String AGENTS_MD =
      """
      # AGENTS.md —— 项目级 Agent 行为说明

      <!-- 每次组装 prompt 时重新读取并注入 system prompt，改完下一轮即生效。 -->

      ## 这个工作区是做什么的

      （写这个工作区里所有 Agent 共享的背景：业务领域、术语、不可逾越的边界。）

      ## 通用约定

      - 回答用中文。
      - 需要读写文件时，先说明要动哪个路径、为什么。
      """;

  /** 默认人格模板：Agent 未在 {@code AGENT.md} 的 {@code identity} 段声明人格时生效。 */
  private static final String SOUL_MD =
      """
      # SOUL.md —— 默认人格定义

      <!-- Agent 在 AGENT.md 的 identity 段声明了人格时以那份为准，本文件提供缺省。 -->

      你是一个务实、直接的助手。先给结论，再给依据；不确定的事说不确定，不要编。
      """;

  /** 用户偏好模板：本文件由用户手工维护，OryxOS 只读。 */
  private static final String USER_MD =
      """
      # USER.md —— 用户偏好

      <!-- 本文件由你手工维护，OryxOS 只读不写。Agent 自己攒的记忆在 memory/MEMORY.md。 -->

      - 称呼：
      - 常用语言：
      - 关注领域：
      """;

  /** 要生成的文件：路径相对工作区根 + 初始内容。 */
  private static final List<Template> FILES =
      List.of(
          new Template("memory/MEMORY.md", MEMORY_MD),
          new Template("mcp_servers.yaml", MCP_SERVERS_YAML),
          new Template("AGENTS.md", AGENTS_MD),
          new Template("SOUL.md", SOUL_MD),
          new Template("USER.md", USER_MD));

  private final Workspace workspace;

  /**
   * @param workspace 工作区；{@code init} 用它的 {@code root()} 作为创建位置
   */
  public InitCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    Path root = workspace.root();
    List<String> created = new ArrayList<>();
    List<String> existing = new ArrayList<>();
    // 库文件是否已存在必须在打开之前问——open 本身就会把文件建出来
    boolean databaseExisted = Files.exists(workspace.dbFile());
    try {
      for (String directory : DIRECTORIES) {
        createDirectory(root.resolve(directory), root, created, existing);
      }
      for (Template template : FILES) {
        createFile(
            root.resolve(template.relativePath()), template.content(), root, created, existing);
      }
    } catch (IOException e) {
      throw new IllegalStateException("创建工作区 " + root + " 失败：" + e.getMessage(), e);
    }
    List<String> schemaChanges;
    try (WorkspaceDb database = WorkspaceDb.open(workspace.dbFile())) {
      // 先补列、再建表：建表脚本里有依赖列的索引（memory_entries.scope），旧表缺列时先建索引会直接失败
      schemaChanges = database.applySchemaDrift();
      database.runSchemaScript();
    }
    if (databaseExisted) {
      existing.add(label(root, workspace.dbFile()));
    } else {
      created.add(label(root, workspace.dbFile()));
    }
    report(root, created, existing, schemaChanges);
  }

  private static void createDirectory(
      Path directory, Path root, List<String> created, List<String> existing) throws IOException {
    if (Files.isDirectory(directory)) {
      existing.add(label(root, directory) + "/");
      return;
    }
    Files.createDirectories(directory);
    created.add(label(root, directory) + "/");
  }

  private static void createFile(
      Path file, String content, Path root, List<String> created, List<String> existing)
      throws IOException {
    if (Files.exists(file)) {
      existing.add(label(root, file));
      return;
    }
    Path parent = file.getParent();
    if (parent == null) {
      // 模板路径都是「目录/文件」两段，正常到不了这里；真到了就报清楚，而不是让 NPE 冒到用户面前
      throw new IllegalStateException("模板路径没有父目录，无法创建：" + file);
    }
    Files.createDirectories(parent);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    created.add(label(root, file));
  }

  /** 相对工作区根展示，如 {@code .oryxos/agents}——绝对路径在长目录树下会把输出撑得没法读。 */
  private static String label(Path root, Path path) {
    return root.getFileName() + "/" + root.relativize(path).toString().replace('\\', '/');
  }

  private static void report(
      Path root, List<String> created, List<String> existing, List<String> schemaChanges) {
    System.out.println("工作区: " + root);
    reportSection("新建", created);
    reportSection("已有（未覆盖）", existing);
    System.out.println();
    System.out.println("数据库表结构:");
    if (schemaChanges.isEmpty()) {
      System.out.println("  与 db/schema.sql 一致（无需补列）");
    } else {
      for (String change : schemaChanges) {
        System.out.println("  " + change);
      }
    }
    System.out.println();
    System.out.println(
        "下一步: `oryxos profile create <name>` 建一个 Agent，再 `oryxos chat --profile <name>` 开始对话");
  }

  private static void reportSection(String title, List<String> items) {
    System.out.println();
    System.out.println(title + ":");
    if (items.isEmpty()) {
      System.out.println("  （无）");
      return;
    }
    for (String item : items) {
      System.out.println("  " + item);
    }
  }

  /**
   * 一个待生成的文件。
   *
   * @param relativePath 相对工作区根的路径，如 {@code memory/MEMORY.md}
   * @param content 文件初始内容
   */
  private record Template(String relativePath, String content) {}
}
