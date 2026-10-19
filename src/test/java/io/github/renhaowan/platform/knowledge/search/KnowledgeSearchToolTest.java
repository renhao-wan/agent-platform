package io.github.renhaowan.platform.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.knowledge.KnowledgeProperties;
import io.github.renhaowan.platform.knowledge.search.KnowledgeSearchService.SearchHit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock
    private KnowledgeSearchService searchService;

    private KnowledgeProperties properties;
    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeProperties();
        tool = new KnowledgeSearchTool(searchService, properties);
    }

    @Test
    void blankQueryReturnsUsageHintInsteadOfSearching() {
        assertThat(tool.execute(staff(), "{\"query\":\"  \"}")).isEqualTo(KnowledgeSearchTool.USAGE_HINT);
        assertThat(tool.execute(staff(), "{}")).isEqualTo(KnowledgeSearchTool.USAGE_HINT);
        verifyNoInteractions(searchService);
    }

    @Test
    void malformedJsonReturnsUsageHint() {
        assertThat(tool.execute(staff(), "not-json")).isEqualTo(KnowledgeSearchTool.USAGE_HINT);
        verifyNoInteractions(searchService);
    }

    @Test
    void emptyHitsYieldsNotFoundText() {
        when(searchService.search(eq("住宿标准"), anyInt(), anyString())).thenReturn(List.of());

        String result = tool.execute(staff(), "{\"query\":\"住宿标准\"}");

        assertThat(result).contains("未在知识库中检索到").contains("住宿标准");
    }

    @Test
    void rendersSourceAndScoreForEachHit() {
        when(searchService.search(eq("住宿标准"), eq(2), eq("STAFF"))).thenReturn(List.of(
                new SearchHit("差旅报销制度", "住宿标准", "一线城市每晚不超过 600 元。", 0.82),
                new SearchHit("差旅报销制度", "", "第二条正文。", Double.NaN)));

        String result = tool.execute(staff(), "{\"query\":\"住宿标准\",\"topK\":2}");

        assertThat(result)
                .contains("[来源：差旅报销制度 > 住宿标准]（相关度 0.82）")
                .contains("一线城市每晚不超过 600 元。")
                .contains("[来源：差旅报销制度]")
                .doesNotContain("NaN");
    }

    @Test
    void defaultsTopKFromPropertiesAndPassesNullRoleForAnonymous() {
        when(searchService.search(eq("审批权限"), eq(properties.getTopK()), isNull())).thenReturn(List.of());

        tool.execute(null, "{\"query\":\"审批权限\"}");

        verify(searchService).search("审批权限", properties.getTopK(), null);
    }

    @Test
    void searchFailureDegradesToText() {
        when(searchService.search(anyString(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("pgvector down"));

        String result = tool.execute(staff(), "{\"query\":\"报销流程\"}");

        assertThat(result).isEqualTo("知识库检索暂不可用，请稍后再试。");
    }

    @Test
    void callbackIsAdvertisedButNotExecutable() {
        assertThat(tool.callback().getToolDefinition().name()).isEqualTo(KnowledgeSearchTool.TOOL_NAME);
        assertThat(tool.callback().getToolDefinition().description())
                .contains("报销制度")
                .contains("不要用本工具");
        assertThat(tool.handles(KnowledgeSearchTool.TOOL_NAME)).isTrue();
        assertThat(tool.handles("search_rooms")).isFalse();
    }

    private static UserPrincipal staff() {
        return new UserPrincipal(1L, "STAFF");
    }
}
