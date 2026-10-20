package io.github.renhaowan.platform.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    @Test
    void splitsByH2AndDropsH1() {
        MarkdownChunker chunker = new MarkdownChunker(100, 20, 10);
        String markdown = """
                # 差旅报销制度

                ## 住宿标准
                一线城市每晚不超过 600 元，二线城市不超过 400 元。

                ## 超支处理
                超过上述标准的，需提前获得部门负责人书面同意。
                """;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("住宿标准");
        assertThat(chunks.get(0).text()).contains("一线城市").doesNotContain("差旅报销制度");
        assertThat(chunks.get(1).section()).isEqualTo("超支处理");
    }

    @Test
    void prefixedChunkCarriesDocumentAndSection() {
        KnowledgeChunk chunk = new KnowledgeChunk("超支处理", "超过上述标准的，需审批。");

        assertThat(chunk.prefixed("差旅报销制度"))
                .isEqualTo("差旅报销制度 > 超支处理\n超过上述标准的，需审批。");
        assertThat(new KnowledgeChunk("", "正文").prefixed("文档")).isEqualTo("文档\n正文");
        assertThat(new KnowledgeChunk("章节", "正文").prefixed("")).isEqualTo("章节\n正文");
    }

    @Test
    void oversizedSectionSplitsBySubheadingsFirst() {
        MarkdownChunker chunker = new MarkdownChunker(80, 20, 10);
        String markdown = """
                ## 总则

                ### 适用范围
                本制度适用于公司全体正式员工，以及实习期超过三个月的实习生。

                ### 生效日期
                本制度自 2026 年 1 月 1 日起施行，此前发布的相关规定同时废止。
                """;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("总则 > 适用范围");
        assertThat(chunks.get(1).section()).isEqualTo("总则 > 生效日期");
    }

    @Test
    void hardSplitKeepsOverlap() {
        MarkdownChunker chunker = new MarkdownChunker(100, 20, 10);
        String longText = "甲".repeat(250);
        String markdown = "## 长文\n" + longText;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        assertThat(chunks.get(0).text()).hasSize(100);
        // 第二块从第一块的第 90 个字符开始，重叠 10 字符
        assertThat(chunks.get(1).text().substring(0, 10))
                .isEqualTo(chunks.get(0).text().substring(90, 100));
    }

    @Test
    void smallSectionMergesIntoNeighbour() {
        MarkdownChunker chunker = new MarkdownChunker(500, 20, 10);
        String markdown = """
                ## 主体
                这是一段足够长的正文内容，用来保证它不会被认为是一个过小的块。

                ## 附则
                即日生效。
                """;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(1);
        // 合并保留前一块的 section 名，两块正文都还在
        assertThat(chunks.get(0).text()).contains("足够长的正文内容").contains("即日生效");
    }

    @Test
    void leadingIntroKeepsFirstSectionHeadingWhenMerged() {
        MarkdownChunker chunker = new MarkdownChunker(500, 50, 10);
        String markdown = """
                # 差旅费用标准

                本文件规定员工因公出差的标准。

                ## 住宿标准
                一线城市每晚不超过 600 元，二线城市不超过 400 元。超过标准的需提前审批。
                """;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(1);
        // 导语并入首节时，标题必须取首节的"住宿标准"而不是导语的空标题
        assertThat(chunks.get(0).section()).isEqualTo("住宿标准");
        assertThat(chunks.get(0).text()).contains("本文件规定").contains("一线城市");
    }

    @Test
    void codeBlockIsNeverSplit() {
        MarkdownChunker chunker = new MarkdownChunker(100, 20, 10);
        String code = "```java\n" + "System.out.println(\"长行\");\n".repeat(20) + "```";
        String markdown = "## 示例\n" + code;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).startsWith("```java").endsWith("```");
    }

    @Test
    void oversizedTableSplitsByRowsAndRepeatsHeader() {
        MarkdownChunker chunker = new MarkdownChunker(120, 20, 10);
        StringBuilder table = new StringBuilder("| 城市 | 上限 |\n| --- | --- |\n");
        for (int i = 0; i < 10; i++) {
            table.append("| 城市").append(i).append(" | ").append(400 + i).append(" 元 |\n");
        }
        String markdown = "## 住宿标准\n" + table;

        List<KnowledgeChunk> chunks = chunker.chunk(markdown);

        assertThat(chunks.size()).isGreaterThan(1);
        for (KnowledgeChunk chunk : chunks) {
            assertThat(chunk.text()).startsWith("| 城市 | 上限 |");
        }
    }

    @Test
    void firstH1IsExtractedAndFrontMatterIsNotPartOfBody() {
        String markdown = """
                ---
                title: 差旅报销制度
                visible_permission: admin:policy
                ---
                # 差旅报销制度

                ## 住宿标准
                一线城市每晚不超过 600 元。
                """;

        FrontMatter frontMatter = FrontMatter.parse(markdown);

        assertThat(frontMatter.title()).isEqualTo("差旅报销制度");
        assertThat(frontMatter.visiblePermission()).isEqualTo("admin:policy");
        assertThat(frontMatter.body()).doesNotContain("visible_permission");
        assertThat(MarkdownChunker.firstH1(frontMatter.body())).isEqualTo("差旅报销制度");
    }

    @Test
    void defaultVisiblePermissionIsPublic() {
        assertThat(FrontMatter.parse("# 标题\n正文").visiblePermission()).isEqualTo("public");
        assertThat(FrontMatter.parse("---\ntitle: x\n---\n正文").visiblePermission()).isEqualTo("public");
    }
}
