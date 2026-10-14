package io.oryxlabs.oryxos.cli.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工作区根路径解析与「是否在工作区内」判定（contracts/cli-surface.md §3、data-model.md §7）。
 *
 * <p><b>工作区的定义只有一条</b>：某目录是工作区 ⟺ 它下面存在 {@code .oryxos/} 子目录。判定标准写在一处，所有命令
 * 共用同一个结论——十二条命令各写一遍「怎样才算工作区」，迟早会有一条写得不一样。
 *
 * <p><b>根路径基准是进程当前目录</b>：{@code oryxos init} 在当前目录建 {@code .oryxos/}，其余命令也在当前目录找它。
 * 本节不引入「工作区根可配置」的配置键——没有调用方会设它，凭空多一个配置键就是凭空多一处真相（章程原则 II）。
 *
 * <p><b>轻命令与重命令共用本类</b>：轻命令不经 Spring，直接 {@link #current()} 取；重命令由装配类注册成 Bean
 * （contracts/cli-assembly.md §5）。两条路径的根路径算法是同一个方法，不会漂移。
 */
public final class Workspace {

  /** 工作区目录名；{@code oryxos init} 在当前目录建出它。 */
  private static final String WORKSPACE_DIR = ".oryxos";

  /** Agent 子目录名，{@code profile *} 与 Agent 解析都读它。 */
  private static final String AGENTS_DIR = "agents";

  /** SQLite 库文件名，轻命令的原生 JDBC 路径连它。 */
  private static final String DB_FILE = "oryxos.db";

  private final Path root;

  /**
   * @param baseDir 基准目录（工作区所在的那一层）；{@code .oryxos/} 建在它下面
   */
  public Workspace(Path baseDir) {
    this.root = baseDir.toAbsolutePath().normalize().resolve(WORKSPACE_DIR);
  }

  /**
   * 以进程当前目录为基准解析工作区。
   *
   * @return 当前目录下的 {@code .oryxos/}；<b>不检查它是否存在</b>——存在性由 {@link #requireExists()} 单独判定
   */
  public static Workspace current() {
    return new Workspace(Paths.get(""));
  }

  /**
   * @return 工作区根目录（{@code <基准目录>/.oryxos}），绝对路径且已归一化
   */
  public Path root() {
    return root;
  }

  /**
   * @return Agent 目录（{@code .oryxos/agents}）；目录名即 Agent 名
   */
  public Path agentsDir() {
    return root.resolve(AGENTS_DIR);
  }

  /**
   * @return SQLite 库文件（{@code .oryxos/oryxos.db}）
   */
  public Path dbFile() {
    return root.resolve(DB_FILE);
  }

  /**
   * @return 当前目录是否已经是工作区
   */
  public boolean exists() {
    return Files.isDirectory(root);
  }

  /**
   * 断言当前目录是工作区，不是则抛错。
   *
   * <p><b>抛异常而不是 {@code System.exit}</b>：退出码由 Picocli 的异常处理器统一给出（1），手工 exit 会跳过它
   * （contracts/cli-surface.md §3）。消息里写清「缺什么、下一步做什么」——只报「工作区不存在」等于让用户去猜。
   *
   * @return 工作区根目录，便于调用方接着用
   * @throws IllegalStateException 当前目录不是工作区
   */
  public Path requireExists() {
    if (!exists()) {
      throw new IllegalStateException(
          "当前目录不是 OryxOS 工作区（找不到 " + WORKSPACE_DIR + "/ 目录）：请先运行 `oryxos init` 创建工作区");
    }
    return root;
  }
}
