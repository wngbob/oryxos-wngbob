package io.oryxlabs.oryxos.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 装配：把 {@code oryxos.providers} 清单变成一张**显式映射表** {@code 厂商名 → ChatModel}。
 *
 * <p><b>本类的核心约束</b>（章程原则 III、FR-005）：MUST NOT 通过扫描 Spring 容器里 {@link ChatModel} 类型的 Bean
 * 来建立这份映射。多厂商并存时各家模型实现类型不同、但都对 {@code ChatModel} 这一接口编程，按类型扫描必有歧义；
 * 更糟的是扫描结果取决于容器里碰巧注册了什么，读代码的人看不出"谁对谁"。这里改成按配置<b>逐条追加</b>： 名字和实现写死在一起，一眼可见，且厂商数量不改变查表复杂度。
 *
 * <p>新增一家厂商（SC-001 的兑现方式）：① 在 {@code oryxos.providers} 追加一项；② 在 {@link #providerMap} 的
 * 构造分支里为该名字加一条实现分支；③ 在 {@code oryxos-provider/pom.xml} 加对应模型依赖。Agent 侧零改动。
 *
 * <p><b>注入提示</b>：本 Bean 的返回类型同时是 Spring "按类型收集 Bean 成 Map" 的语法，消费方请用
 * {@code @Qualifier("providerMap")} 指名本 Bean，避免拿到按类型收集的空表。
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderConfiguration {

  /**
   * 按配置逐条构造模型，组成显式映射表。
   *
   * @param properties 全局层配置；构造前先 {@link ProviderProperties#validate()}，坏配置在启动期就报错
   * @return 不可变映射表，键为厂商名
   */
  @Bean
  public Map<String, ChatModel> providerMap(ProviderProperties properties) {
    properties.validate();
    Map<String, ChatModel> providers = new LinkedHashMap<>();
    for (ProviderProperties.ProviderEntry entry : properties.providers()) {
      if ("deepseek".equals(entry.name())) {
        providers.put(entry.name(), deepSeek(entry));
      }
    }
    return Map.copyOf(providers);
  }

  /**
   * 构造 DeepSeek 模型。
   *
   * <p>刻意<b>不</b>调用 {@code DeepSeekApi.builder().baseUrl(...)}：配置契约只定义 {@code name} 与 {@code
   * api-key} 两个键，端点走 SDK 默认值，不凭空造配置键。
   *
   * <p>{@code defaultOptions} 里的模型名只是兜底——每次调用的真实模型由 Profile 的 {@code provider.model} 决定，随请求 options
   * 下发。
   */
  private static ChatModel deepSeek(ProviderProperties.ProviderEntry entry) {
    return DeepSeekChatModel.builder()
        .deepSeekApi(DeepSeekApi.builder().apiKey(entry.apiKey()).build())
        .defaultOptions(
            DeepSeekChatOptions.builder().model(DeepSeekApi.ChatModel.DEEPSEEK_CHAT).build())
        .build();
  }
}
