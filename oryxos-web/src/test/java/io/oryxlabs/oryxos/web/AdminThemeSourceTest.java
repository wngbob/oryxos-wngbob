package io.oryxlabs.oryxos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理台「与官网同一套设计语言」的机器判卷（FR-014，2026-10-20 用户裁决后调整）。
 *
 * <p><b>为什么需要它</b>：设计令牌的唯一真相源是 {@code website/.vitepress/theme/custom.css}，管理台只是逐字引用它。
 * 靠人眼「看着差不多」守不住这条一致性——官网改了品牌色、管理台忘了跟，两边会静默分叉。故这里直接读两个文件逐字比对。
 *
 * <p><b>判的是哪两段</b>：① 浅色档（官网 {@code :root} ↔ 管理台第一段 {@code :root}）； ② 深色档（官网 {@code .dark} ↔ 管理台
 * {@code @media (prefers-color-scheme: dark)} 里的 {@code :root}）。 管理台用媒体查询而不是 {@code class="dark"}
 * 落地深色档，是为了跟官网一致地「跟随系统」，而不是固定深色。
 *
 * <p>2026-10-20 起还判第三样：管理台的 logo（{@code src/assets/logo-icon.svg}）逐字节等于官网 {@code
 * public/images/logo-icon.svg}——「参考首页把 logo 加上」由机器守，不靠人眼。
 */
class AdminThemeSourceTest {

  private static final Path FRONTEND_ROOT = Paths.get("src", "main", "frontend");
  private static final Path INDEX_HTML = FRONTEND_ROOT.resolve("index.html");
  private static final Path TOKENS_CSS =
      FRONTEND_ROOT.resolve(Paths.get("src", "styles", "tokens.css"));
  private static final Path LOGO_SVG =
      FRONTEND_ROOT.resolve(Paths.get("src", "assets", "logo-icon.svg"));

  /** 官网主题样式：设计令牌的唯一真相源（与本模块同仓，测试工作目录是模块根目录）。 */
  private static final Path SITE_CSS =
      Paths.get("..", "website", ".vitepress", "theme", "custom.css");

  /** 官网徽标：侧边栏与概览页用的就是同一张图（2026-10-20 用户裁决「参考首页把 logo 加上」）。 */
  private static final Path SITE_LOGO =
      Paths.get("..", "website", "public", "images", "logo-icon.svg");

  private static final String LIGHT_ANCHOR = ":root {";
  private static final String SITE_DARK_ANCHOR = ".dark {";
  private static final String DARK_MEDIA_ANCHOR = "@media (prefers-color-scheme: dark)";

  @Test
  @DisplayName("index.html 不再硬编码深色 —— 与官网一致地默认浅色、跟随系统")
  void indexHtmlDoesNotForceDarkAppearance() throws IOException {
    assertThat(read(INDEX_HTML))
        .as("管理台 MUST NOT 固定深色（2026-10-20 用户裁决：不再要求「深色底」）")
        .doesNotContain("class=\"dark\"");
  }

  @Test
  @DisplayName("浅色令牌逐字取自官网 :root —— 改官网不跟这里会红")
  void lightTokensMirrorSiteRootBlock() throws IOException {
    assertThat(block(read(TOKENS_CSS), LIGHT_ANCHOR))
        .as("tokens.css 第一段 :root 必须与 website 的 :root 逐字相同")
        .isEqualTo(block(read(SITE_CSS), LIGHT_ANCHOR));
  }

  @Test
  @DisplayName("管理台 logo 逐字节取自官网 —— 改官网不跟这里会红")
  void adminLogoMirrorsSiteIcon() throws IOException {
    assertThat(read(LOGO_SVG))
        .as("src/assets/logo-icon.svg 必须与 website/public/images/logo-icon.svg 完全一致")
        .isEqualTo(read(SITE_LOGO));
  }

  @Test
  @DisplayName("深色令牌逐字取自官网 .dark —— 由系统偏好触发，不是固定外观")
  void darkTokensMirrorSiteDarkBlock() throws IOException {
    String tokens = read(TOKENS_CSS);
    int media = tokens.indexOf(DARK_MEDIA_ANCHOR);
    assertThat(media).as("tokens.css 应有 prefers-color-scheme: dark 段").isGreaterThan(-1);

    String adminDark = block(tokens.substring(media), LIGHT_ANCHOR);
    assertThat(adminDark)
        .as("管理台的深色档必须与官网 .dark 逐字相同")
        .isEqualTo(block(read(SITE_CSS), SITE_DARK_ANCHOR));
  }

  /**
   * 取出锚点后第一个配平花括号块的内容。
   *
   * @param css 样式原文
   * @param anchor 块起始锚点（含左花括号）
   * @return 花括号内的原文（行首缩进已归一：深色档嵌在 media 里会多一层缩进，那不是「值不同」）
   */
  private static String block(String css, String anchor) {
    int start = css.indexOf(anchor);
    assertThat(start).as("样式里找不到锚点 %s", anchor).isGreaterThan(-1);
    int bodyStart = start + anchor.length();
    int depth = 1;
    for (int i = bodyStart; i < css.length(); i++) {
      char c = css.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return css.substring(bodyStart, i).replaceAll("(?m)^[ \\t]+", "").trim();
        }
      }
    }
    throw new AssertionError("样式里 " + anchor + " 的花括号没有配平");
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
