package io.oryxlabs.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.core.agent.AgentLifecycleService;
import io.oryxlabs.oryxos.core.agent.AgentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 工作区三端点的验收测试（第 30 节 FR-006，contracts/workspace-memory.md §1）。
 *
 * <p>守的是<b>目录穿越</b>与两条写入路径的分岔：{@code agents/<name>/AGENT.md} 必须走生命周期编排（校验 + 重注册），
 * 其余文件直接落盘。守卫写错一处就足以读到 {@code /etc/passwd}，故三条越界形态（{@code ../}、URL 编码、绝对路径）各断一条。
 *
 * <p><b>{@link AgentStore} 是真的、落在临时工作区上</b>：守卫就是它的 {@code resolveSafe}，替身会把要判的那段代码整个跳过。
 * 真工作区用静态初始化块建好——Spring 上下文在 {@code @BeforeAll} 之前就刷新。
 */
@WebMvcTest(controllers = WorkspaceApiController.class)
@Import(WorkspaceApiControllerTest.WorkspaceTestConfig.class)
class WorkspaceApiControllerTest {

  private static final String AGENT = "weather";

  /** 越界写入的目标文件名：放在工作区父目录上，断言它<b>没有</b>被创建。 */
  private static final String ESCAPED_FILE = "escaped-" + UUID.randomUUID() + ".txt";

  /** 超过 1 MiB 的文件（判据是「先 size 后读」，这个文件若被整读进内存，测试机不一定炸，但契约已经破了）。 */
  private static final int OVERSIZED_BYTES = 1024 * 1024 + 1;

  /** 临时工作区根（相当于 {@code <基准>/.oryxos}）。 */
  private static final Path ROOT =
      Paths.get(System.getProperty("java.io.tmpdir"), "oryxos-workspace-" + UUID.randomUUID());

  static {
    try {
      Path agentDir = Files.createDirectories(ROOT.resolve("agents").resolve(AGENT));
      Files.writeString(agentDir.resolve("AGENT.md"), "# weather\n", StandardCharsets.UTF_8);
      Files.writeString(agentDir.resolve("REFERENCE.md"), "# 参考\n", StandardCharsets.UTF_8);
      Files.write(agentDir.resolve("big.txt"), new byte[OVERSIZED_BYTES]);
      Path archived = Files.createDirectories(ROOT.resolve("archive").resolve("old"));
      Files.writeString(archived.resolve("AGENT.md"), "# old\n", StandardCharsets.UTF_8);
      // 工作区里不该出现在 tree 里的目录：证明端点只遍历 agents/ 与 archive/
      Files.createDirectories(ROOT.resolve("logs"));
    } catch (IOException e) {
      throw new IllegalStateException("准备工作区测试目录失败：" + ROOT, e);
    }
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AgentLifecycleService lifecycleService;

  @AfterAll
  static void cleanup() {
    deleteRecursively(ROOT);
  }

  @TestConfiguration
  static class WorkspaceTestConfig {

    @Bean
    AgentStore agentStore() {
      return new AgentStore(ROOT);
    }
  }

  @Test
  @DisplayName("tree 只返回 agents/ 与 archive/ 两个根，其余目录不出现")
  void treeReturnsAgentsAndArchiveRoots() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("agents"))
        .andExpect(jsonPath("$.data[0].directory").value(true))
        .andExpect(jsonPath("$.data[1].name").value("archive"));
  }

  @Test
  @DisplayName("tree 能逐层展开到 Agent 目录里的文件，路径是相对 .oryxos 的形式")
  void treeCanDescendIntoAgentDirectory() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].children[0].name").value(AGENT))
        .andExpect(jsonPath("$.data[0].children[0].path").value("agents/" + AGENT))
        .andExpect(
            jsonPath("$.data[0].children[0].children[0].path").value("agents/weather/AGENT.md"))
        .andExpect(jsonPath("$.data[1].children[0].path").value("archive/old"));
  }

  @Test
  @DisplayName("file?path=../../etc/passwd 被拒（400），一个字节都不读")
  void fileTraversalOutsideWorkspaceIsRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(containsString("escapes workspace")));
  }

  @Test
  @DisplayName("URL 编码的穿越同样读不到界外文件（真容器解码成 ../ 归 400；MockMvc 不解码时按工作区内文件名判 404）")
  void urlEncodedTraversalIsRejected() throws Exception {
    // 真 Tomcat 会把 query 里的 %2F 解码，控制器拿到的就是 ../../etc/passwd（由上一条用例判 400）；
    // MockMvc 不在这一层解码，于是它按「工作区内一个名叫 ..%2F..%2Fetc%2Fpasswd 的文件」处理 → 不存在 → 404。
    // 两种结局都是拒绝；这里断的是「无论如何都读不到 /etc/passwd 的内容」，比只断状态码更强
    mockMvc
        .perform(get("/api/v1/workspace/file?path=..%2F..%2Fetc%2Fpasswd"))
        .andExpect(status().is4xxClientError())
        .andExpect(content().string(not(containsString("root:"))));
  }

  @Test
  @DisplayName("绝对路径被拒（400）")
  void absolutePathIsRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "/etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(containsString("必须是相对工作区的路径")));
  }

  @Test
  @DisplayName("正常读文件返回 {path, content}")
  void normalFileReturnsContent() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "agents/" + AGENT + "/AGENT.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("agents/weather/AGENT.md"))
        .andExpect(jsonPath("$.data.content").value("# weather\n"));
  }

  @Test
  @DisplayName("超过 1 MiB 的文件被拒（400），且不把它整份读进内存")
  void oversizedFileIsRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "agents/" + AGENT + "/big.txt"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(containsString("1 MiB")));
  }

  @Test
  @DisplayName("写文件时穿越被拒（400），且目标文件确实没有被创建")
  void writeTraversalIsRejectedAndNothingIsWritten() throws Exception {
    String body = "{\"path\":\"../" + ESCAPED_FILE + "\",\"content\":\"x\"}";

    mockMvc
        .perform(
            post("/api/v1/workspace/file").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(Files.exists(ROOT.resolve("..").resolve(ESCAPED_FILE))).isFalse();
    verifyNoInteractions(lifecycleService);
  }

  @Test
  @DisplayName("写 agents/<name>/AGENT.md 走生命周期编排（校验 + 重注册），不直接落盘")
  void writingAgentMarkdownDelegatesToLifecycleUpdate() throws Exception {
    String markdown = "---\nprovider:\n  name: mock\n  model: mock-model\n---\n正文";
    String body =
        "{\"path\":\"agents/"
            + AGENT
            + "/AGENT.md\",\"content\":\""
            + markdown.replace("\n", "\\n")
            + "\"}";

    mockMvc
        .perform(
            post("/api/v1/workspace/file").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data").doesNotExist());

    verify(lifecycleService).update(eq(AGENT), eq(markdown));
  }

  private static void deleteRecursively(Path path) {
    try (var walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              file -> {
                try {
                  Files.deleteIfExists(file);
                } catch (IOException ignored) {
                  // 清理失败不影响判卷；临时目录在 /tmp 下，由系统回收
                }
              });
    } catch (IOException ignored) {
      // 同上
    }
  }
}
