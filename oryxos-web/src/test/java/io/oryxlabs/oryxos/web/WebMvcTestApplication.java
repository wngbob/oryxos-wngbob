package io.oryxlabs.oryxos.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测试专用的启动配置锚点。
 *
 * <p><b>为什么必须有</b>：{@code oryxos-web} 是库模块，发行物里没有任何 {@code @SpringBootApplication}（真正的启动类在 {@code
 * oryxos-cli}）。而 {@code @WebMvcTest} 会向上找 {@code @SpringBootConfiguration}，找不到就直接失败。
 * 这里放一个只为测试存在的锚点，不进发行物。
 *
 * <p><b>为什么用完整的 {@code @SpringBootApplication} 而不是只写 {@code @SpringBootConfiguration} +
 * {@code @EnableAutoConfiguration}</b>：后者没有 {@code @ComponentScan}，于是
 * {@code @WebMvcTest(controllers = ProbeController.class)} 那行形同虚设——实测所有探针路径都落到静态资源处理器（{@code
 * resource not found: probe/...}）。{@code @WebMvcTest} 的切片过滤器会把扫描结果裁到 MVC 相关类型，故本锚点不会把业务 Bean 带进切片。
 */
@SpringBootApplication
public class WebMvcTestApplication {}
