package io.oryxlabs.oryxos.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.sandbox.ActionType;
import io.oryxlabs.oryxos.tool.sandbox.SandboxAction;
import io.oryxlabs.oryxos.tool.sandbox.SandboxViolationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件工具的两条硬约束：正常能跑通、越界被拦且 IO 一次都没发生（contracts/builtin-tools.md §3.3）。
 *
 * <p><b>越界用例的目标是刻意挑过的</b>：每一个都选成「若先做 IO 就会得到一个 {@code ToolResult} 而不是异常」的形态 （目录 / 父目录缺失 /
 * 普通文件）。这样「抛出 {@code SandboxViolationException}」这一条断言才真的证明了校验在前 ——否则一个「先读文件、读完再校验」的实现会让同样的断言照绿。
 */
class FileToolsTest {

  @TempDir Path tempDir;

  private static FileTools permissive() {
    return new FileTools(new RecordingSandbox(true));
  }

  @Nested
  @DisplayName("read_file")
  class ReadFile {

    @Test
    @DisplayName("read_file 正常路径：返回文件全文")
    void readFile_existingFile_returnsContent() throws IOException {
      Path target =
          Files.writeString(tempDir.resolve("note.txt"), "你好\nworld", StandardCharsets.UTF_8);

      ToolResult result = permissive().readFile(target.toString(), null, null);

      assertThat(result.success()).isTrue();
      assertThat(result.content()).isEqualTo("你好\nworld");
    }

    @Test
    @DisplayName("read_file 越界时必须被拦，且 IO 一次都没发生")
    void readFile_outsideWhitelist_isBlockedBeforeIo() throws IOException {
      RecordingSandbox sandbox = new RecordingSandbox(false);
      // 目标故意选成「目录」：先做 IO 的话 read_file 会返回 ToolResult.failure("不是普通文件")，不会抛异常。
      // 拿到异常 ⇒ 那一步 IO 从未走到。
      Path target = Files.createDirectory(tempDir.resolve("a-directory"));

      assertThatThrownBy(() -> new FileTools(sandbox).readFile(target.toString(), null, null))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.FILE_READ, target.toString()));
    }

    @Test
    @DisplayName("read_file 分页：offset/limit 取到指定行段，行号与 grep 的口径一致（都是 1 起）")
    void readFile_offsetAndLimit_returnsRequestedRange() throws IOException {
      Path target =
          Files.writeString(
              tempDir.resolve("five.txt"), "l1\nl2\nl3\nl4\nl5", StandardCharsets.UTF_8);

      ToolResult result = permissive().readFile(target.toString(), 2, 2);

      assertThat(result.success()).isTrue();
      // 第 2 行起读 2 行 ⇒ l2、l3。第 1 行不能出现，第 4 行也不能
      assertThat(result.content()).startsWith("l2\nl3");
      assertThat(result.content()).doesNotContain("l1").doesNotContain("l4");
    }

    @Test
    @DisplayName("read_file 超过默认上限：只给前 2000 行，并给出续读用的 offset")
    void readFile_overDefaultLimit_truncatesAndTellsHowToContinue() throws IOException {
      StringBuilder many = new StringBuilder();
      for (int i = 1; i <= 2001; i++) {
        many.append("line-").append(i).append('\n');
      }
      Path target =
          Files.writeString(tempDir.resolve("long.txt"), many.toString(), StandardCharsets.UTF_8);

      ToolResult result = permissive().readFile(target.toString(), null, null);

      assertThat(result.success()).isTrue();
      // 2000 行正文 + 1 行截断说明
      assertThat(result.content().lines()).hasSize(2001);
      assertThat(result.content()).contains("已截断").contains("用 offset=2001 继续");
      assertThat(result.content()).contains("line-2000").doesNotContain("line-2001");
    }

    @Test
    @DisplayName("read_file offset 越过末尾：给成功结果与说明，不抛异常（那不是「读错了」）")
    void readFile_offsetBeyondEnd_returnsEmptyNote() throws IOException {
      Path target =
          Files.writeString(tempDir.resolve("tiny.txt"), "a\nb\nc", StandardCharsets.UTF_8);

      ToolResult result = permissive().readFile(target.toString(), 99, null);

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("超出末尾");
    }

    @Test
    @DisplayName("read_file 参数非法：offset 从 1 起，传 0 是不可重试失败")
    void readFile_offsetBelowOne_fails() throws IOException {
      Path target =
          Files.writeString(tempDir.resolve("tiny.txt"), "a\nb\nc", StandardCharsets.UTF_8);

      ToolResult result = permissive().readFile(target.toString(), 0, null);

      assertThat(result.success()).isFalse();
      assertThat(result.retryable()).isFalse();
      assertThat(result.errorMessage()).contains("offset 从 1 起");
    }
  }

  @Nested
  @DisplayName("write_file")
  class WriteFile {

    @Test
    @DisplayName("write_file 正常路径：写入内容并报出字节数与目标路径")
    void writeFile_newFile_writesContentAndReportsBytes() throws IOException {
      Path target = tempDir.resolve("out.txt");

      ToolResult result = permissive().writeFile(target.toString(), "hello");

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("5 字节").contains(target.toString());
      assertThat(Files.readString(target)).isEqualTo("hello");
    }

    @Test
    @DisplayName("write_file 父目录不存在时明确报错，不自动创建目录")
    void writeFile_missingParentDirectory_failsWithoutCreatingIt() {
      Path target = tempDir.resolve("no-such-dir").resolve("out.txt");

      ToolResult result = permissive().writeFile(target.toString(), "hello");

      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("父目录不存在");
      assertThat(Files.exists(target.getParent())).isFalse();
    }

    @Test
    @DisplayName("write_file 越界时必须被拦，且文件一次都没被写过")
    void writeFile_outsideWhitelist_isBlockedBeforeIo() {
      RecordingSandbox sandbox = new RecordingSandbox(false);
      // 目标故意选在「父目录不存在」的位置：先做 IO 的话会返回 ToolResult.failure("父目录不存在")，不会抛异常。
      Path target = tempDir.resolve("missing-parent").resolve("out.txt");

      assertThatThrownBy(() -> new FileTools(sandbox).writeFile(target.toString(), "hello"))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.FILE_WRITE, target.toString()));
      assertThat(Files.exists(target)).isFalse();
    }
  }

  @Nested
  @DisplayName("list_dir")
  class ListDir {

    @Test
    @DisplayName("list_dir 正常路径：每行「类型 + 名字」，按名字排序")
    void listDir_directory_listsEntriesSortedByName() throws IOException {
      Files.writeString(tempDir.resolve("b.txt"), "b");
      Files.writeString(tempDir.resolve("a.txt"), "a");
      Files.createDirectory(tempDir.resolve("c-dir"));

      ToolResult result = permissive().listDir(tempDir.toString());

      assertThat(result.success()).isTrue();
      assertThat(result.content().lines())
          .containsExactly("[文件] a.txt", "[文件] b.txt", "[目录] c-dir");
    }

    @Test
    @DisplayName("list_dir 路径不是目录时明确报错")
    void listDir_regularFile_fails() throws IOException {
      Path target = Files.writeString(tempDir.resolve("a-file.txt"), "x");

      ToolResult result = permissive().listDir(target.toString());

      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("不是目录");
    }

    @Test
    @DisplayName("list_dir 越界时必须被拦，且目录一次都没被列过")
    void listDir_outsideWhitelist_isBlockedBeforeIo() throws IOException {
      RecordingSandbox sandbox = new RecordingSandbox(false);
      // 目标故意选成「普通文件」：先做 IO 的话 list_dir 会返回 ToolResult.failure("不是目录")，不会抛异常。
      Path target = Files.writeString(tempDir.resolve("a-file.txt"), "x");

      assertThatThrownBy(() -> new FileTools(sandbox).listDir(target.toString()))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.FILE_READ, target.toString()));
    }
  }

  @Nested
  @DisplayName("edit_file")
  class EditFile {

    @Test
    @DisplayName("edit_file 正常路径：oldText 唯一匹配时替换成功，报出替换位置的行号")
    void editFile_uniqueOldText_replacesAndReportsLineNumber() throws IOException {
      Path target =
          Files.writeString(
              tempDir.resolve("cfg.txt"), "第一行\n温度 = 0.7\n第三行\n", StandardCharsets.UTF_8);

      ToolResult result = permissive().editFile(target.toString(), "0.7", "0.8");

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("第 2 行").contains("0.7").contains("0.8");
      assertThat(Files.readString(target, StandardCharsets.UTF_8))
          .isEqualTo("第一行\n温度 = 0.8\n第三行\n");
    }

    @Test
    @DisplayName("edit_file 找不到 oldText → 报错，且文件内容逐字节不变")
    void editFile_oldTextNotFound_failsWithoutTouchingFile() throws IOException {
      byte[] original = "第一行\n温度 = 0.7\n".getBytes(StandardCharsets.UTF_8);
      Path target = tempDir.resolve("cfg.txt");
      Files.write(target, original);

      ToolResult result = permissive().editFile(target.toString(), "不存在的片段", "新内容");

      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("找不到");
      // SC-007：报错时文件被改动次数为 0——逐字节比对，不是「内容看起来差不多」
      assertThat(Files.readAllBytes(target)).isEqualTo(original);
    }

    @Test
    @DisplayName("edit_file 出现多次 → 报错（附出现次数），且文件内容逐字节不变")
    void editFile_oldTextAppearsTwice_failsWithoutTouchingFile() throws IOException {
      byte[] original = "0.7\n别的行\n0.7\n".getBytes(StandardCharsets.UTF_8);
      Path target = tempDir.resolve("cfg.txt");
      Files.write(target, original);

      ToolResult result = permissive().editFile(target.toString(), "0.7", "0.8");

      assertThat(result.success()).isFalse();
      assertThat(result.errorMessage()).contains("多次");
      assertThat(Files.readAllBytes(target)).isEqualTo(original);
    }

    @Test
    @DisplayName("edit_file 越界时必须被拦，且文件一次都没被写过")
    void editFile_outsideWhitelist_isBlockedBeforeIo() throws IOException {
      RecordingSandbox sandbox = new RecordingSandbox(false);
      // 目标故意选成「目录」：先做 IO 的话会返回 ToolResult.failure("不是普通文件")，不会抛异常
      Path target = Files.createDirectory(tempDir.resolve("a-directory"));

      assertThatThrownBy(() -> new FileTools(sandbox).editFile(target.toString(), "a", "b"))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.FILE_WRITE, target.toString()));
    }
  }

  @Nested
  @DisplayName("grep")
  class Grep {

    @Test
    @DisplayName("grep 正常路径：目录里递归命中，每行「路径:行号: 行内容」")
    void grep_directory_returnsPathLineNumberAndLine() throws IOException {
      Files.writeString(tempDir.resolve("a.txt"), "alpha\nbeta\n", StandardCharsets.UTF_8);
      Path nested = Files.createDirectory(tempDir.resolve("nested"));
      Files.writeString(nested.resolve("b.txt"), "gamma beta\n", StandardCharsets.UTF_8);

      ToolResult result = permissive().grep("beta", tempDir.toString(), null, null);

      assertThat(result.success()).isTrue();
      assertThat(result.content().lines())
          .containsExactly(
              tempDir.resolve("a.txt") + ":2: beta", nested.resolve("b.txt") + ":1: gamma beta");
    }

    @Test
    @DisplayName("grep 命中超过 200 条 → 只返回 200 条并注明总命中数")
    void grep_moreThanLimit_truncatesAndNotesTotalCount() throws IOException {
      StringBuilder many = new StringBuilder();
      for (int i = 0; i < 201; i++) {
        many.append("hit-").append(i).append('\n');
      }
      Files.writeString(tempDir.resolve("many.txt"), many.toString(), StandardCharsets.UTF_8);

      ToolResult result = permissive().grep("hit-", tempDir.toString(), null, null);

      assertThat(result.success()).isTrue();
      // 200 条命中 + 1 行截断说明
      assertThat(result.content().lines()).hasSize(201);
      assertThat(result.content()).contains("已截断").contains("201");
    }

    @Test
    @DisplayName("grep 目录里有二进制文件时跳过它，整次搜索不中断")
    void grep_binaryFileInDirectory_isSkippedWithoutInterrupting() throws IOException {
      Files.writeString(tempDir.resolve("text.txt"), "needle here\n", StandardCharsets.UTF_8);
      // 非法 UTF-8 字节序列：解码即失败
      Files.write(tempDir.resolve("blob.bin"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01});
      // 全零字节：恰好是「合法 UTF-8」，解码器放它过去，靠 NUL 检查拦下
      Files.write(tempDir.resolve("zeros.bin"), new byte[] {0x00, 0x00, 0x00});

      ToolResult result = permissive().grep("needle", tempDir.toString(), null, null);

      assertThat(result.success()).isTrue();
      assertThat(result.content()).contains("needle here");
      assertThat(result.content()).doesNotContain("blob.bin").doesNotContain("zeros.bin");
    }

    @Test
    @DisplayName("grep 正则非法 → 不可重试失败（重试同一个正则不会变好）")
    void grep_invalidRegex_fails() {
      ToolResult result = permissive().grep("([", tempDir.toString(), null, null);

      assertThat(result.success()).isFalse();
      assertThat(result.retryable()).isFalse();
      assertThat(result.errorMessage()).contains("正则表达式非法");
    }

    @Test
    @DisplayName("grep 越界时必须被拦，且一次 IO 都没发生")
    void grep_outsideWhitelist_isBlockedBeforeIo() {
      RecordingSandbox sandbox = new RecordingSandbox(false);
      Path target = tempDir.resolve("missing");

      assertThatThrownBy(() -> new FileTools(sandbox).grep("x", target.toString(), null, null))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.FILE_READ, target.toString()));
    }

    @Test
    @DisplayName("grep 翻页：第二页接着第一页，不重不漏，且不再误报截断")
    void grep_offset_pagesWithoutGapsOrOverlap() throws IOException {
      StringBuilder many = new StringBuilder();
      for (int i = 0; i < 300; i++) {
        many.append("hit-").append(i).append('\n');
      }
      Path target =
          Files.writeString(tempDir.resolve("many.txt"), many.toString(), StandardCharsets.UTF_8);

      ToolResult first = permissive().grep("hit-", tempDir.toString(), null, null);
      ToolResult second = permissive().grep("hit-", tempDir.toString(), 201, null);

      assertThat(first.content().lines()).hasSize(201);
      assertThat(first.content()).contains("用 offset=201 继续");
      // 第一页的最后一条与第二页的第一条恰好相接：1~200 / 201~300
      assertThat(first.content().lines().toList().get(199)).endsWith(":200: hit-199");
      assertThat(second.content().lines()).hasSize(100);
      assertThat(second.content().lines().toList().get(0)).endsWith(":201: hit-200");
      assertThat(second.content().lines().toList().get(99)).endsWith(":300: hit-299");
      // 第二页已是末尾：翻页翻到底不该再出现截断说明（判据是「后面还有没有」，不是「本页装没装满」）
      assertThat(second.content()).doesNotContain("已截断");
    }

    @Test
    @DisplayName("grep contextLines：命中行用「:」、上下文行用「-」，重叠的上下文不重复出现")
    void grep_contextLines_marksContextDifferentlyFromMatches() throws IOException {
      Path target =
          Files.writeString(
              tempDir.resolve("ctx.txt"), "a\nneedle-1\nb\nneedle-2\nc", StandardCharsets.UTF_8);

      ToolResult result = permissive().grep("needle", target.toString(), null, 1);

      assertThat(result.success()).isTrue();
      // 第 3 行同时落在两次命中的上下文里，只出现一次
      assertThat(result.content().lines())
          .containsExactly(
              target + ":1- a",
              target + ":2: needle-1",
              target + ":3- b",
              target + ":4: needle-2",
              target + ":5- c");
    }

    @Test
    @DisplayName("grep contextLines：上下文不连续时用「--」标出中间跳过了内容")
    void grep_contextLines_gapBetweenGroups_isMarked() throws IOException {
      Path target =
          Files.writeString(
              tempDir.resolve("gap.txt"),
              "a\nneedle-1\nb\nc\nd\nneedle-2\ne",
              StandardCharsets.UTF_8);

      ToolResult result = permissive().grep("needle", target.toString(), null, 1);

      assertThat(result.content().lines())
          .containsExactly(
              target + ":1- a",
              target + ":2: needle-1",
              target + ":3- b",
              "--",
              target + ":5- d",
              target + ":6: needle-2",
              target + ":7- e");
    }
  }

  @Nested
  @DisplayName("glob")
  class Glob {

    @Test
    @DisplayName("glob 正常路径：命中嵌套文件与根下文件（**/ 可匹配零层目录），按路径排序")
    void glob_matchesNestedAndRootLevelFiles_sortedByPath() throws IOException {
      Files.writeString(tempDir.resolve("Root.java"), "class Root {}");
      Path nested = Files.createDirectories(tempDir.resolve("src").resolve("main"));
      Files.writeString(nested.resolve("App.java"), "class App {}");
      Files.writeString(tempDir.resolve("notes.md"), "not java");

      ToolResult result = permissive().glob("**/*.java", tempDir.toString(), null);

      assertThat(result.success()).isTrue();
      assertThat(result.content().lines()).containsExactly("Root.java", "src/main/App.java");
    }

    @Test
    @DisplayName("glob 命中超过 200 条 → 恰好 200 条并带截断说明")
    void glob_moreThanLimit_truncatesAndNotes() throws IOException {
      for (int i = 0; i < 201; i++) {
        Files.writeString(tempDir.resolve("f" + i + ".txt"), "x");
      }

      ToolResult result = permissive().glob("*.txt", tempDir.toString(), null);

      assertThat(result.success()).isTrue();
      assertThat(result.content().lines()).hasSize(201);
      assertThat(result.content()).contains("已截断").contains("201");
    }

    @Test
    @DisplayName("glob 越界时必须被拦，且一次 IO 都没发生")
    void glob_outsideWhitelist_isBlockedBeforeIo() {
      RecordingSandbox sandbox = new RecordingSandbox(false);
      Path target = tempDir.resolve("missing");

      assertThatThrownBy(() -> new FileTools(sandbox).glob("*.txt", target.toString(), null))
          .isInstanceOf(SandboxViolationException.class);

      assertThat(sandbox.actions())
          .containsExactly(new SandboxAction(ActionType.FILE_READ, target.toString()));
    }

    @Test
    @DisplayName("glob 翻页：两页合起来恰好是全部匹配，不重不漏")
    void glob_offset_pagesWithoutGapsOrOverlap() throws IOException {
      List<String> all = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        String name = "f" + i + ".txt";
        Files.writeString(tempDir.resolve(name), "x");
        all.add(name);
      }
      all.sort(Comparator.naturalOrder());

      ToolResult first = permissive().glob("*.txt", tempDir.toString(), null);
      ToolResult second = permissive().glob("*.txt", tempDir.toString(), 201);

      assertThat(first.content().lines()).hasSize(201);
      assertThat(first.content()).contains("用 offset=201 继续");
      assertThat(second.content().lines()).hasSize(100);
      assertThat(second.content()).doesNotContain("已截断");

      List<String> paged = new ArrayList<>(first.content().lines().limit(200).toList());
      paged.addAll(second.content().lines().toList());
      // 两页拼起来正好是全量且无重复——「不漏」由 containsExactly 保证，「不重」由它同样保证
      assertThat(paged).containsExactlyElementsOf(all);
    }
  }

  @Test
  @DisplayName("顺序断言：write_file 的 enforce 被调用时文件还不存在，放行之后才出现（校验先于 IO）")
  void enforce_happensBeforeIo_forWriteFile() {
    RecordingSandbox sandbox = new RecordingSandbox(true);
    Path target = tempDir.resolve("order.txt");

    ToolResult result = new FileTools(sandbox).writeFile(target.toString(), "hello");

    assertThat(result.success()).isTrue();
    assertThat(sandbox.actions()).hasSize(1);
    // 反序实现（先写后校验）下这里会是 true——这条断言才是「顺序」的证据，「抛了异常」不是
    assertThat(sandbox.targetExistedWhenEnforced(0)).isFalse();
    assertThat(Files.exists(target)).isTrue();
  }
}
