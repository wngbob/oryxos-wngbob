package io.oryxlabs.oryxos.web.controller.dto;

import java.util.Map;

/**
 * 健康状态的对外视图（data-model.md §3.3）。
 *
 * <p><b>只报「配置就绪」，不发真实请求</b>（FR-020）：{@code providers} 说的是「这个厂商的 key 配了没有」，
 * 不是「这个厂商这会儿通不通」——后者要花一次真调用，健康检查不该有副作用。
 *
 * @param status 整体状态，**只由 {@code db} 决定**（库不通就是 DOWN）
 * @param db 数据库探活结果：{@code UP} / {@code DOWN}
 * @param providers 各厂商的配置就绪情况
 */
public record HealthView(String status, String db, Map<String, String> providers) {

  /** 防御性拷贝：厂商表收敛为不可变视图，调用方改不动已经交出去的响应对象。 */
  public HealthView {
    providers = providers == null ? Map.of() : Map.copyOf(providers);
  }
}
