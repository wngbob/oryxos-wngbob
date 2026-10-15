package io.oryxlabs.oryxos.tool.builtin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 HTML → 纯文本（{@code web_fetch} 的实现细节）。
 *
 * <p><b>为什么包级私有</b>：它是 {@link HttpTools} 的一个实现步骤，不是本节交付物里的一项。放进 public 就多一个对外概念 （软门禁
 * ①），而外面没有任何调用方需要它。
 *
 * <p><b>为什么不引 Jsoup</b>：软门禁 ⑥——本次不新增任何第三方依赖。核心阶段要的只是「把标签去掉、把实体解回来」， 一条正则流水线足够；DOM 树、CSS
 * 选择器、容错解析都不在工具契约里。
 *
 * <p><b>为什么每个 Pattern 都是 {@code static final}</b>：{@code Pattern.compile} 每次调用都重编译一遍正则，而这里每抓一个网页
 * 要跑七八遍。P3C 的 {@code AvoidPatternCompileInMethodRule} 盯的正是这件事。
 *
 * <p><b>正则里没有嵌套量词</b>（{@code (a+)+} 那种）：输入是不可信的网页内容，嵌套量词会把一次抓取变成指数级回溯（REDOS）。 这里全是「取反字符类 +
 * 惰性量词」的形态，回溯是线性的。
 *
 * <p><b>顺序不能调</b>：先剥标签、再解实体。反过来的话，页面里写成 {@code &lt;script&gt;} 的<b>文字</b>会被解码成 {@code
 * <script>}，接着被当成真标签连内容一起吃掉——用户想看的代码片段就消失了。
 */
final class WebPageText {

  /** HTML 注释。{@code (?s)} 让 {@code .} 跨行匹配。 */
  private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

  /** {@code <script>} 整块连内容：脚本源码进模型上下文只是噪音。{@code (?i)} 忽略大小写，{@code (?s)} 跨行。 */
  private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b[^>]*>.*?</script\\s*>");

  /** {@code <style>} 整块连内容，同理。 */
  private static final Pattern STYLE = Pattern.compile("(?is)<style\\b[^>]*>.*?</style\\s*>");

  /**
   * 块级标签换成换行。
   *
   * <p>不做这一步，整页文字会挤成一行：{@code <p>第一段</p><p>第二段</p>} 去标签后是「第一段第二段」，模型读不出段落边界。
   */
  private static final Pattern BLOCK_BOUNDARY =
      Pattern.compile(
          "(?i)</?(?:p|div|br|li|tr|h[1-6]|section|article|header|footer|nav|ul|ol|table|pre)\\b[^>]*>");

  /** 其余标签一律去掉，只留标签之间的文字。 */
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** 命名字符实体。{@code &#39;} 走 {@link #NUMERIC_ENTITY}，不在这里重复。 */
  private static final Pattern NAMED_ENTITY = Pattern.compile("&(amp|lt|gt|quot|apos|nbsp);");

  /** 数字字符实体：{@code &#123;} 与 {@code &#x1F600;} 两种写法。 */
  private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

  /** 水平空白（含制表符、{@code \r}）连续多个压成一个空格。 */
  private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");

  /** 三行以上的空行压成两行——段落之间留一个空行就够，多出来的是标签换行留下的。 */
  private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");

  /**
   * 换行符。
   *
   * <p>单列成常量是因为它出现在 {@code for} 的表达式里——P3C 的 {@code UndefineMagicConstantRule} 禁止条件里出现魔法值。
   */
  private static final String LINE_BREAK = "\n";

  private WebPageText() {}

  /**
   * 把一段 HTML 转成纯文本。
   *
   * @param html 原始响应体
   * @return 去掉注释 / 脚本 / 样式 / 标签并解码实体后的文本，行首尾空白已去除
   */
  static String toPlainText(String html) {
    String text = COMMENT.matcher(html).replaceAll("");
    text = SCRIPT.matcher(text).replaceAll("");
    text = STYLE.matcher(text).replaceAll("");
    text = BLOCK_BOUNDARY.matcher(text).replaceAll("\n");
    text = TAG.matcher(text).replaceAll("");
    text = decodeEntities(text);
    text = HORIZONTAL_SPACE.matcher(text).replaceAll(" ");
    text = BLANK_RUN.matcher(text).replaceAll("\n\n");
    return stripLines(text);
  }

  /**
   * 解码命名字符实体与数字字符实体。
   *
   * <p>{@link Matcher#quoteReplacement} 不能省：替换结果里可能出现 {@code $} 或 {@code \}（{@code &#36;} 就是 {@code
   * $}）， 而 {@code replaceAll} 会把它们当成分组引用去解析，轻则结果错、重则抛异常。
   */
  private static String decodeEntities(String text) {
    String decoded =
        NAMED_ENTITY
            .matcher(text)
            .replaceAll(match -> Matcher.quoteReplacement(named(match.group(1))));
    return NUMERIC_ENTITY
        .matcher(decoded)
        .replaceAll(match -> Matcher.quoteReplacement(codePoint(match.group(1))));
  }

  /** 命名字符实体表。表外的名字正则根本不会匹配到，{@code default} 只是为了让返回值有类型。 */
  private static String named(String name) {
    switch (name) {
      case "amp":
        return "&";
      case "lt":
        return "<";
      case "gt":
        return ">";
      case "quot":
        return "\"";
      case "apos":
        return "'";
      case "nbsp":
        return " ";
      default:
        return "";
    }
  }

  /** 数字实体：{@code x} 开头按十六进制解。畸形或越界的实体丢弃，不为一个坏实体中断整页转换。 */
  private static String codePoint(String digits) {
    boolean hex = digits.startsWith("x") || digits.startsWith("X");
    try {
      int value = Integer.parseInt(hex ? digits.substring(1) : digits, hex ? 16 : 10);
      return Character.isValidCodePoint(value) ? Character.toString(value) : "";
    } catch (NumberFormatException e) {
      return "";
    }
  }

  /** 逐行去掉首尾空白；这一步同时消掉标签换行留下的行首缩进。 */
  private static String stripLines(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (String line : text.split(LINE_BREAK, -1)) {
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(line.strip());
    }
    return out.toString().strip();
  }
}
