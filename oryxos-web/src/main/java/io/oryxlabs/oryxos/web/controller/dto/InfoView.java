package io.oryxlabs.oryxos.web.controller.dto;

/**
 * 运行时概况的对外视图（data-model.md §3.3）。
 *
 * @param name 产品名
 * @param version 版本号；读不到时为 {@code "unknown"}
 * @param javaVersion 运行时 Java 版本
 * @param agents **可用** Agent 数（加载失败的项不算；它们在 {@code /profiles} 里以 {@code valid:false} 呈现）
 * @param activeSessions 活跃会话数
 * @param registeredTools 已注册工具数
 * @param uptimeSeconds 进程已运行秒数
 */
public record InfoView(
    String name,
    String version,
    String javaVersion,
    int agents,
    long activeSessions,
    int registeredTools,
    long uptimeSeconds) {}
