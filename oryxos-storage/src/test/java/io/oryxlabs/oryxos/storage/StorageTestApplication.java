package io.oryxlabs.oryxos.storage;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 仅测试用的最小 Spring Boot 配置：给 {@code @DataJpaTest} 一个 {@code @SpringBootConfiguration} 落脚点。
 *
 * <p>{@code oryxos-storage} 是库模块、没有启动类，而 {@code @DataJpaTest} 必须找到一个启动配置才会建上下文。 实体与 Repository
 * 都在本包之下，自动扫描即可覆盖，故此处不额外声明扫描路径。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class StorageTestApplication {}
