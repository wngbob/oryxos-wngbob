package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.agent.AgentLifecycleService;
import io.oryxlabs.oryxos.core.agent.AgentStore;
import io.oryxlabs.oryxos.core.agent.FileNode;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.FileContent;
import io.oryxlabs.oryxos.web.controller.dto.WriteFileRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 工作区浏览与单文件读写（第 30 节 FR-006，contracts/workspace-memory.md §1）。
 *
 * <p><b>路径守卫不在这里</b>：三条端点全部经 {@link AgentStore#resolveSafe}，守卫只有那一处——分散写守卫的结果是「某一条入口忘了
 * normalize」，而那一条就足以读到 {@code /etc/passwd}。
 *
 * <p><b>写 {@code AGENT.md} 与写别的文件是两条路</b>：改 {@code agents/<name>/AGENT.md} 等于改这个 Agent 的定义，必须走
 * {@link AgentLifecycleService#update}（解析校验 + 写 + 重注册）；其余文件（{@code REFERENCE.md}、{@code
 * scripts/*}）直接落盘， 它们不影响运行时。
 */
@RestController
@RequestMapping("/api/v1/workspace")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是对外入口本身（第 30 节 FR-006），不是缺陷")
public class WorkspaceApiController {

  /** 单个文件读取上限（契约 §1.3）：先 {@code Files.size} 判，MUST NOT 整文件读入内存再判。 */
  private static final long MAX_FILE_BYTES = 1024L * 1024L;

  /** {@code agents/<name>/AGENT.md} 的形状：只有它走生命周期编排。 */
  private static final Pattern AGENT_MARKDOWN = Pattern.compile("^agents/([^/]+)/AGENT\\.md$");

  private final AgentStore agentStore;
  private final AgentLifecycleService lifecycleService;

  /**
   * @param agentStore 工作区磁盘读写（守卫在它里面）
   * @param lifecycleService 改 {@code AGENT.md} 时的编排入口
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "两个协作对象都是 Spring 注入的单例：AgentStore 持有工作区根、lifecycle 持有运行时索引，副本会让「刚写完的定义」看不见")
  public WorkspaceApiController(AgentStore agentStore, AgentLifecycleService lifecycleService) {
    this.agentStore = agentStore;
    this.lifecycleService = lifecycleService;
  }

  /**
   * @return {@code agents/} 与 {@code archive/} 两棵子树
   */
  @GetMapping("/tree")
  public ApiResponse<List<FileNode>> tree() {
    return ApiResponse.ok(agentStore.tree());
  }

  /**
   * 读一个文本文件。
   *
   * @param path 相对 {@code .oryxos} 根的路径
   * @return 路径 + 内容
   */
  @GetMapping("/file")
  public ApiResponse<FileContent> read(@RequestParam("path") String path)
      throws NoResourceFoundException {
    Path file = agentStore.resolveSafe(path);
    if (!Files.isRegularFile(file)) {
      throw noSuchFile(path);
    }
    long size;
    try {
      size = Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件大小失败：" + path + "（" + e.getMessage() + "）", e);
    }
    if (size > MAX_FILE_BYTES) {
      throw new IllegalArgumentException("文件超过 1 MiB 上限: " + path);
    }
    String content;
    try {
      content = Files.readString(file, StandardCharsets.UTF_8);
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("不是 UTF-8 文本文件: " + path, e);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败：" + path + "（" + e.getMessage() + "）", e);
    }
    return ApiResponse.ok(new FileContent(path, content));
  }

  /**
   * 写一个文本文件；命中 {@code agents/<name>/AGENT.md} 时走生命周期编排（写完立刻生效）。
   *
   * @param request 路径 + 内容
   * @return 空数据的成功信封
   */
  @PostMapping("/file")
  public ApiResponse<Void> write(@RequestBody WriteFileRequest request) {
    String path = request.path();
    String content = request.content() == null ? "" : request.content();
    // 先过守卫再分岔：两条路都必须防穿越，且必须与 store 用的是同一个判据
    Path target = agentStore.resolveSafe(path);
    var matcher = AGENT_MARKDOWN.matcher(path);
    if (matcher.matches()) {
      lifecycleService.update(matcher.group(1), content);
      return ApiResponse.ok(null);
    }
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败：" + path + "（" + e.getMessage() + "）", e);
    }
    return ApiResponse.ok(null);
  }

  /**
   * 404 走 {@code NoResourceFoundException}：它已有 404 映射，包成 {@code IllegalArgumentException} 会退化成 400。
   */
  private static NoResourceFoundException noSuchFile(String path) {
    return new NoResourceFoundException(HttpMethod.GET, path);
  }
}
