package io.github.renhaowan.platform.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService.SearchHit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeEvalServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private KnowledgeSearchService searchService;

    private KnowledgeProperties properties;
    private KnowledgeEvalService service;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeProperties();
        properties.setEvalFile(tempDir.resolve("questions.jsonl").toString());
        service = new KnowledgeEvalService(searchService, properties);
    }

    @Test
    void ranksHitsAndComputesRecallAndMrr() throws Exception {
        write("""
                {"question":"住宿上限","expected":["差旅费用标准 > 住宿标准"]}
                {"question":"报销时限","expected":["差旅费用标准 > 报销时限"]}
                """);
        when(searchService.search(anyLong(), anyString(), anyInt(), any(), anyString())).thenAnswer(call -> {
            String question = call.getArgument(1);
            if ("住宿上限".equals(question)) {
                return List.of(hit("差旅费用标准", "住宿标准"));
            }
            // 第二名才命中
            return List.of(hit("无关文档", "其他"), hit("差旅费用标准", "报销时限"));
        });

        KnowledgeEvalService.EvalReport report = service.evaluate(4);

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.recallAtK()).isEqualTo(1.0);
        assertThat(report.mrr()).isCloseTo(0.75, within(0.001));
        assertThat(report.emptyRate()).isZero();
        assertThat(report.details()).extracting(KnowledgeEvalService.EvalDetail::rank)
                .containsExactly(1, 2);
    }

    @Test
    void emptyResultCountsAsMissAndEmptyRate() throws Exception {
        write("{\"question\":\"不存在的问题\",\"expected\":[\"某文档 > 某章节\"]}\n");
        when(searchService.search(anyLong(), anyString(), anyInt(), any(), anyString())).thenReturn(List.of());

        KnowledgeEvalService.EvalReport report = service.evaluate(4);

        assertThat(report.matched()).isZero();
        assertThat(report.recallAtK()).isZero();
        assertThat(report.emptyRate()).isEqualTo(1.0);
    }

    @Test
    void passesPermissionsFromQuestionFile() throws Exception {
        write("{\"question\":\"冲突裁决\",\"expected\":[\"会议室管理员操作规范 > 冲突裁决\"],\"permissions\":[\"*\"]}\n");
        when(searchService.search(0L, "冲突裁决", 4, Set.of("*"), "eval"))
                .thenReturn(List.of(hit("会议室管理员操作规范", "冲突裁决")));

        assertThat(service.evaluate(4).matched()).isEqualTo(1);
    }

    @Test
    void missingQuestionFileFailsLoudly() {
        assertThatThrownBy(() -> service.evaluate(4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("评估集不存在");
    }

    private void write(String content) throws Exception {
        Files.writeString(Path.of(properties.getEvalFile()), content, StandardCharsets.UTF_8);
    }

    private static SearchHit hit(String title, String section) {
        return new SearchHit(title, section, "正文", 0.8);
    }
}
