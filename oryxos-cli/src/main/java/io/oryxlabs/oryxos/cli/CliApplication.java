package io.oryxlabs.oryxos.cli;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 重命令（{@code chat} / {@code serve} / {@code gateway}）的 Spring 启动类（contracts/cli-assembly.md §2）。
 *
 * <p><b>为什么不复用 {@code OryxosApplication}</b>：{@code oryxos-boot} 聚合 {@code oryxos-cli}（{@code boot
 * → cli}），反向即成环。启动类只能建在本模块。
 *
 * <p><b>{@code @EntityScan} / {@code @EnableJpaRepositories} MUST
 * 显式写</b>：{@code @SpringBootApplication} 的 {@code scanBasePackages} <b>带不动</b>这两个——它们的默认扫描根是主类自己的包
 * （{@code io.oryxlabs.oryxos.cli}），而实体与 Repository 都在兄弟包 {@code io.oryxlabs.oryxos.storage} 下。不写就会在
 * 启动日志里报 {@code Found 0 JPA repository interfaces}，然后重命令在取 Bean 时才炸。第 17 节 {@code
 * OryxosApplication} 已踩过这个坑。
 *
 * <p><b>修订注（2026-10-19，第 26 节）：上一条「不起 SERVLET 容器 / MUST NOT 依赖 {@code oryxos-web}」已被推翻。</b>{@code
 * serve} 起 SERVLET 容器、{@code oryxos-cli} 加了到 {@code oryxos-web} 的依赖边， 故本类必须扫到 {@code
 * io.oryxlabs.oryxos.web}。{@code chat} / {@code gateway} 仍用 {@code
 * WebApplicationType.NONE}——是否起容器由命令决定，不由本类决定。原「本节」指第 18 节。
 *
 * <p><b>扫描根 MUST NOT 放宽到 {@code io.oryxlabs.oryxos}</b>：第 17 节的 {@code
 * io.oryxlabs.oryxos.boot.OryxosApplication} 曾与本节主类在 fat JAR 里共存，放宽扫描根会把它自己的 {@code @EntityScan} /
 * {@code @EnableJpaRepositories} 也注册一遍，重命令撞 {@code BeanDefinitionOverrideException}（当时实测 {@code
 * chat --message} 100% 复现）。<b>修订注（2026-10-19）：那个类已按第 26 节裁决 ③ 删除，但「逐包列举、不放宽到根」这条纪律不随它走</b>——
 * 根包下每多一个带 Spring 注解的类，放宽扫描都会悄悄把它拉进来，而这类错误在本模块的单测里看不见。
 *
 * <p><b>为什么逐包列举</b>：本上下文需要的配置类只有 {@link CliAssemblyConfiguration}（{@code cli}）、{@code
 * io.oryxlabs.oryxos.provider.ProviderConfiguration}（{@code provider}），以及第 26 节新增的 {@code
 * io.oryxlabs.oryxos.web}（控制器、统一异常出口、Web 配置、探活组件带注解）；core / tool / memory / storage / channel-cli
 * 的类刻意不带 Spring 注解，Bean 一律在 {@link CliAssemblyConfiguration} 里显式声明（contracts/cli-assembly.md
 * §3）。将来若真在别的包里新增了带注解的类，MUST 在本行补上——不补不会被装配，且不会报错。
 *
 * <p>本类不含任何逻辑——它的全部作用是把「从哪个包扫 Bean、扫哪些实体与 Repository」这三条写在一处。
 */
@SpringBootApplication(
    scanBasePackages = {
      "io.oryxlabs.oryxos.cli", // CliApplication + CliAssemblyConfiguration
      "io.oryxlabs.oryxos.provider", // ProviderConfiguration
      "io.oryxlabs.oryxos.web" // 第 26 节：控制器 / 统一异常出口 / WebConfig / DatabaseHealthProbe
    })
@EntityScan("io.oryxlabs.oryxos.storage.entity")
@EnableJpaRepositories("io.oryxlabs.oryxos.storage.repository")
public class CliApplication {}
