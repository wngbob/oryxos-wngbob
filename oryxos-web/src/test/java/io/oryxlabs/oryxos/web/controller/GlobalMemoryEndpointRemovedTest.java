package io.oryxlabs.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.core.agent.AgentLifecycleService;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.memory.MemoryService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 全局 {@code GET /api/v1/memory} 被移除（第 30 节 FR-007：记忆改成 per-agent，不再有「全局那一份」这个对外概念）。
 *
 * <p><b>为什么断言分两层</b>：MockMvc 切片只装了本类点名的控制器，别的路径天然 404——只靠它，即使 {@code MemoryApiController}
 * 还在树里也照样绿。故第二层直接扫源码：没有任何控制器再映射这个路径、那个类文件不存在。 两层合起来才说明「端点真没了」，而不是「这一片里没装它」。
 */
@WebMvcTest(controllers = AgentApiController.class)
class GlobalMemoryEndpointRemovedTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AgentService agentService;

  @MockitoBean private ProfileRegistry profileRegistry;

  @MockitoBean private AgentLifecycleService lifecycleService;

  @MockitoBean private MemoryService memoryService;

  @Test
  @DisplayName("GET /api/v1/memory 返回 404，统一信封照旧")
  void globalMemoryEndpointReturns404() throws Exception {
    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("源码里没有任何控制器再映射 /api/v1/memory，MemoryApiController 类文件不存在")
  void noControllerMapsGlobalMemory() throws IOException {
    Path sourceRoot = repoRoot().resolve("oryxos-web/src/main/java");
    List<String> offenders;
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      offenders =
          walk.filter(path -> path.toString().endsWith(".java"))
              .filter(GlobalMemoryEndpointRemovedTest::mentionsGlobalMemoryPath)
              .map(path -> sourceRoot.relativize(path).toString())
              .toList();
    }

    assertThat(offenders).isEmpty();
    assertThat(sourceRoot.resolve("io/oryxlabs/oryxos/web/controller/MemoryApiController.java"))
        .doesNotExist();
  }

  private static boolean mentionsGlobalMemoryPath(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8).contains("/api/v1/memory");
    } catch (IOException e) {
      throw new IllegalStateException("读不了源文件: " + file, e);
    }
  }

  /** 从 surefire 的工作目录（模块目录）往上找仓库根；找不到直接失败，不静默跳过。 */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-boot")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
