package io.oryxlabs.oryxos.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 测试用的重命令上下文启动器：<b>与生产同一条启动路径</b>（同一个 {@link CliApplication}），只把数据源参数直接喂进去。
 *
 * <p><b>为什么不写一份 {@code application.yaml}</b>：本模块的 pom 明令 MUST NOT 放第二份 {@code application.yaml}
 * （唯一那份在 {@code oryxos-boot}，而重命令要读的 {@code spring.datasource.*} 必须与 CLI 的类同 classpath）。测试替身配置文件
 * 会让人误以为「CLI 模块自己也有一份配置」。
 *
 * <p><b>{@code oryxos.providers} 整个不给</b>：空清单能过 {@code ProviderProperties.validate()}，本类要验的是工具装配，
 * 不是模型接线。
 *
 * <p>包级私有：只在 {@code io.oryxlabs.oryxos.cli} 的测试里用，不进任何对外概念清单。
 */
final class CliTestContext {

  private CliTestContext() {}

  /**
   * 启动一次真实上下文。
   *
   * @param dbFile SQLite 库文件（用真实文件而非 {@code :memory:}：HikariCP pool=1 + 内存库能跑，但「生产怎么配这里就怎么配」
   *     的等价性会丢）
   * @param extraSources 额外的配置类（用于让位机制这类需要替换某个 Bean 的场景）
   * @return 启动好的上下文，<b>调用方负责 close</b>
   */
  static ConfigurableApplicationContext boot(Path dbFile, Class<?>... extraSources) {
    return boot(dbFile, Map.of(), extraSources);
  }

  /**
   * 带额外配置项的启动重载。
   *
   * <p><b>为什么需要它</b>：第 22 节的记忆装配要验的正是「{@code oryxos.memory.backend} 取不同值时造出哪一档」与「未知取值报错」，
   * 而这两条都必须把配置喂进去才验得到。直接调 {@code memoryService(...)} 传参看似更轻，但那验不到 {@code @Value} 占位、
   * 也验不到「未知取值让上下文起不来」——而后者才是真正会发生在运维身上的形态。
   *
   * @param dbFile SQLite 库文件
   * @param extraProperties 额外的配置项（覆盖 {@code application.yaml} 的同名键）
   * @param extraSources 额外的配置类
   * @return 启动好的上下文，<b>调用方负责 close</b>
   */
  static ConfigurableApplicationContext boot(
      Path dbFile, Map<String, Object> extraProperties, Class<?>... extraSources) {
    Class<?>[] sources = new Class<?>[extraSources.length + 1];
    System.arraycopy(extraSources, 0, sources, 0, extraSources.length);
    sources[extraSources.length] = CliApplication.class;
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("spring.datasource.url", "jdbc:sqlite:" + dbFile);
    properties.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
    properties.put("spring.datasource.hikari.maximum-pool-size", 1);
    properties.put("spring.jpa.hibernate.ddl-auto", "none");
    properties.put(
        "spring.jpa.properties.hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
    properties.putAll(extraProperties);
    return new SpringApplicationBuilder(sources)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .properties(properties)
        .run();
  }
}
