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
 * <p><b>MUST NOT 加 {@code @EnableWebMvc}、MUST NOT 起 SERVLET 容器</b>（仲裁 15）：本节不起 Web 容器，调用方一律用 {@code
 * WebApplicationType.NONE} 构建上下文。同理本模块 MUST NOT 依赖 {@code oryxos-web}。
 *
 * <p><b>扫描根 MUST NOT 放宽到 {@code io.oryxlabs.oryxos}</b>：发行物 fat JAR 里 {@code
 * io.oryxlabs.oryxos.boot.OryxosApplication}（{@code oryxos-boot} 的启动类）与本节同类共存，它自己也是
 * {@code @SpringBootApplication} 且带同一套 {@code @EntityScan} /
 * {@code @EnableJpaRepositories}。放宽扫描根会把它当配置类注册， 于是 JPA 仓库 Bean 被注册两遍，重命令直接撞 {@code
 * BeanDefinitionOverrideException} 起不来（实测：{@code chat --message} 在 fat JAR 上 100% 复现；模块内测试看不见，因为
 * {@code oryxos-cli} 的 classpath 上没有 {@code oryxos-boot}）。{@code --help}
 * 之类轻命令不启动上下文，所以这个坑只有跑重命令才暴露。
 *
 * <p><b>为什么只列这两个包</b>：本上下文需要的配置类只有 {@link CliAssemblyConfiguration}（{@code cli}）与 {@code
 * io.oryxlabs.oryxos.provider.ProviderConfiguration}（{@code provider}）；core / tool / memory /
 * storage / channel-cli 的类刻意不带 Spring 注解，Bean 一律在 {@link CliAssemblyConfiguration}
 * 里显式声明（contracts/cli-assembly.md §3）。将来若真在第三个包里新增了带注解的类，MUST 在本行补上——不补不会被装配，且不会报错。
 *
 * <p>本类不含任何逻辑——它的全部作用是把「从哪个包扫 Bean、扫哪些实体与 Repository」这三条写在一处。
 */
@SpringBootApplication(
    scanBasePackages = {
      "io.oryxlabs.oryxos.cli", // CliApplication + CliAssemblyConfiguration
      "io.oryxlabs.oryxos.provider" // ProviderConfiguration
    })
@EntityScan("io.oryxlabs.oryxos.storage.entity")
@EnableJpaRepositories("io.oryxlabs.oryxos.storage.repository")
public class CliApplication {}
