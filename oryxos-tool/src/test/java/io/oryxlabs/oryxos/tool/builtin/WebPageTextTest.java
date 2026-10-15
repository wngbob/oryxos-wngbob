package io.oryxlabs.oryxos.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WebPageText} 的守点（contracts/builtin-tools.md §2.12）。
 *
 * <p>它是包级私有的实现细节，但「HTML 去标签」这件事的坑全在细节里——顺序、实体、跨行、大小写——所以单独给它一组用例， 而不是全靠 {@code HttpToolsTest}
 * 里那条端到端的粗断言兜着。
 */
class WebPageTextTest {

  @Test
  @DisplayName("剥标签：注释、script、style 连内容一起去掉，其余标签只去壳留字")
  void toPlainText_stripsTagsAndTheirContent() {
    String html =
        "<html><head><title>标题</title><style>p{color:red}</style>"
            + "<script>var x = 1 < 2;</script></head>"
            + "<body><h1>正文</h1><!-- 这是注释 --><p>段落</p></body></html>";

    String text = WebPageText.toPlainText(html);

    assertThat(text).contains("标题").contains("正文").contains("段落");
    assertThat(text)
        .doesNotContain("<h1>")
        .doesNotContain("color:red")
        .doesNotContain("var x")
        .doesNotContain("这是注释");
  }

  @Test
  @DisplayName("块级标签换成换行：段落不会挤成一行，段与段之间留一个空行")
  void toPlainText_blockTags_becomeLineBreaks() {
    // 收尾标签与起始标签各贡献一个换行 ⇒ 两段之间恰好一个空行
    String text = WebPageText.toPlainText("<p>第一段</p><p>第二段</p>");

    assertThat(text).isEqualTo("第一段\n\n第二段");
  }

  @Test
  @DisplayName("实体解码：命名的与数字的（含十六进制）都解回文字")
  void toPlainText_decodesNamedAndNumericEntities() {
    String text =
        WebPageText.toPlainText("a &amp; b &lt;tag&gt; &quot;q&quot; &#39;s&#39; &#x4e2d;");

    assertThat(text).isEqualTo("a & b <tag> \"q\" 's' 中");
  }

  @Test
  @DisplayName("顺序不能调：页面里写成 &lt;script&gt; 的文字是文字，不能被解码成真标签后吃掉")
  void toPlainText_escapedTags_stayAsText() {
    // 先剥标签、再解实体 ⇒ 这里得到的是字面的 <script> 文字；反过来的话它会被当成真标签，整段消失
    String text = WebPageText.toPlainText("<p>用法：&lt;script&gt;alert(1)&lt;/script&gt;</p>");

    assertThat(text).isEqualTo("用法：<script>alert(1)</script>");
  }

  @Test
  @DisplayName("数字实体解出 $ 时不炸：替换结果里的 $ 不能被当成正则的分组引用")
  void toPlainText_entityDecodingToDollar_doesNotBreakReplacement() {
    // &#36; 是 $，&amp;#36; 会让 naive 的 replaceAll 把 $ 当成分组引用而抛异常
    assertThat(WebPageText.toPlainText("<p>&#36;100 与 &#36;200</p>")).isEqualTo("$100 与 $200");
  }

  @Test
  @DisplayName("空白归一：行首尾去空白、连续水平空白压成一个空格、三行以上空行压成一行空行")
  void toPlainText_normalizesWhitespace() {
    String text =
        WebPageText.toPlainText(
            "<div>  a \t\t b  </div><div></div><div></div><div></div><div>c</div>");

    assertThat(text).isEqualTo("a b\n\nc");
  }

  @Test
  @DisplayName("大小写不敏感：SCRIPT / Script / Style 都按标签处理")
  void toPlainText_tagNames_areCaseInsensitive() {
    String text = WebPageText.toPlainText("<SCRIPT>bad()</SCRIPT><Style>x{}</Style><p>ok</p>");

    assertThat(text).isEqualTo("ok");
  }
}
