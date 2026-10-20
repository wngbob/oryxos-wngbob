package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.MemoryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆只读端点（contracts/rest-endpoints.md §3.2）。
 *
 * <p><b>不缓存</b>（FR-019）：每次请求都问 {@code MemoryService.partition()}，改完立即生效。这里缓存一份等于让管理台展示
 * 「上一次读到的记忆」——运维刚改完看不到变化，只会怀疑记忆没写进去。
 *
 * <p><b>后端异常不折叠成空记忆</b>：交给统一异常出口映射成 500。把「读不到」渲染成「没有记忆」会让运维以为记忆丢了。
 */
@RestController
@RequestMapping("/api/v1/memory")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 26 节交付的对外入口本身（FR-001），不是缺陷")
public class MemoryApiController {

  private final MemoryService memoryService;

  /**
   * @param memoryService 记忆门面（当前 {@code oryxos.memory.backend} 选中的那一档）
   */
  public MemoryApiController(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  /**
   * 读长期记忆的分区视图。
   *
   * @return 核心区全文 + 归档区内容 + 归档区是否被截断
   */
  @GetMapping
  public ApiResponse<MemoryView> get() {
    MemoryPartition partition = memoryService.partition();
    return ApiResponse.ok(
        new MemoryView(partition.core(), partition.archival(), partition.truncated()));
  }
}
