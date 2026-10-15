package io.oryxlabs.oryxos.tool.search;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * 网页搜索的前向接缝（data-model.md §1.8；FR-018）。
 *
 * <p><b>为什么要有这个接缝</b>：换 Google / Bing / Tavily 时只加一个实现类，{@code web_search} 工具与调用方<b>零改动</b>——这正是
 * FR-018 的原话。接缝上多一个 {@link #endpoint()} 也是为这条服务的，理由见该方法的说明。
 *
 * <p><b>不可达时抛 {@link UncheckedIOException}</b>：接缝必须能把「搜不到（正常的空结果）」和「压根没搜成（网络不通）」分开。
 * 返回空列表表达不了后者，而调用方对两者的处理完全不同——前者是成功结果「未找到结果」，后者是<b>可重试</b>失败。用 JDK 自带的 {@link UncheckedIOException}
 * 而不是新造一个异常类型：语义恰好，且不往对外面新增概念。
 */
public interface SearchProvider {

  /**
   * 执行一次搜索。
   *
   * @param query 查询词，<b>原样透传</b>（不做改写、不加过滤词——改写是 provider 的实现细节，不是接缝的职责）
   * @param limit 结果条数上限
   * @return 结果列表；搜到了但没有结果时返回空列表（这是<b>成功</b>，不是失败）
   * @throws UncheckedIOException 网络不通 / 服务端返回非 2xx / 响应无法解析
   */
  List<SearchResult> search(String query, int limit);

  /**
   * 本 provider 的目标端点。
   *
   * <p><b>为什么 URL 长在接缝上、而不是由装配处另传一个字符串</b>：{@code web_search} 的首行沙箱动作是 {@code enforce(HTTP_REQUEST,
   * <目标 URL>)}，而 {@code web_search} 不认识任何具体搜索引擎。若 URL 由装配处传入，换搜索引擎就 <b>既要加实现类、又要改装配</b>——直接违反
   * FR-018「换别的只加实现类、不改工具与调用方」。URL 是 provider 自己的属性。
   *
   * @return 目标端点（校验对象是它的域名部分）
   */
  String endpoint();

  /** 一条搜索结果。 */
  record SearchResult(String title, String url, String snippet) {}
}
