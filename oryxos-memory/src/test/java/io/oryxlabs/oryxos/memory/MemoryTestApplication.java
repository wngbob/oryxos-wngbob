package io.oryxlabs.oryxos.memory;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 仅测试用的最小 Spring Boot 配置：给 {@code SqliteMemoryStoreTest} 的 {@code @DataJpaTest} 一个
 * {@code @SpringBootConfiguration} 落脚点。
 *
 * <p>{@code oryxos-memory} 是库模块、没有启动类，而 {@code @DataJpaTest} 必须找到一个启动配置才会建上下文。被扫描的实体与 Repository
 * 在兄弟模块 {@code oryxos-storage} 的包下，{@code @EnableAutoConfiguration} 的实体扫描覆盖不到—— 故那个测试类自行用
 * {@code @EntityScan} / {@code @EnableJpaRepositories} 指向它们（与 {@code oryxos-boot} 因实体在
 * 兄弟包而必须显式声明是同一条原因）。
 *
 * <p>与 {@code StorageTestApplication} 同款：只做「让切片测试起得来」这一件事，不含任何业务装配。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class MemoryTestApplication {}
