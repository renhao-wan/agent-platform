package io.github.renhaowan.platform.gateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.common.GlobalExceptionHandler;
import io.github.renhaowan.platform.gateway.registry.ContractSyncService;
import io.github.renhaowan.platform.gateway.registry.SourceConflictException;
import io.github.renhaowan.platform.gateway.registry.SourceRegistryService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 来源管理 HTTP 契约：400/404/409 的状态码与消息，以及凭证只入不出。 */
class ToolSourceAdminControllerTest {

    private final SourceRegistryService registry = mock(SourceRegistryService.class);
    private final ContractSyncService sync = mock(ContractSyncService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ToolSourceAdminController(registry, sync))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createRejectsBlankContractUrl() throws Exception {
        mvc.perform(post("/admin/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKey\":\"demo\",\"contractUrl\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(registry, never()).create(any());
    }

    @Test
    void createRejectsSourceKeyWithIllegalCharacters() throws Exception {
        mvc.perform(post("/admin/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKey\":\"demo/../../etc\",\"contractUrl\":\"http://h/v3/api-docs\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(registry, never()).create(any());
    }

    @Test
    void duplicateCreateReturns409() throws Exception {
        when(registry.create(any())).thenThrow(new SourceConflictException("来源已存在：demo"));

        mvc.perform(post("/admin/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKey\":\"demo\",\"contractUrl\":\"http://h/v3/api-docs\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("来源已存在：demo"));
    }

    @Test
    void createSynchronizesImmediatelyAndReturnsView() throws Exception {
        when(registry.create(any())).thenReturn(source("demo"));
        when(sync.sources()).thenReturn(List.of(view("demo")));

        mvc.perform(post("/admin/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceKey\":\"demo\",\"contractUrl\":\"http://h/v3/api-docs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceKey").value("demo"))
                .andExpect(jsonPath("$.data.serviceKeyConfigured").value(true));

        verify(sync).syncByKey("demo");
    }

    @Test
    void updateMissingSourceReturns404() throws Exception {
        when(registry.update(anyString(), any())).thenReturn(Optional.empty());

        mvc.perform(put("/admin/sources/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractUrl\":\"http://h/v3/api-docs\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void retireConfiguredSourceReturns409() throws Exception {
        when(registry.retire("booking")).thenThrow(
                new SourceConflictException("来源 booking 由配置文件管理，请在 platform.gateway.sources 中移除"));

        mvc.perform(delete("/admin/sources/booking"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("platform.gateway.sources")));
    }

    @Test
    void retireMissingSourceReturns404() throws Exception {
        when(registry.retire("missing")).thenReturn(false);

        mvc.perform(delete("/admin/sources/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void syncMissingOrRetiredSourceReturns404() throws Exception {
        when(sync.syncByKey("gone")).thenReturn(Optional.empty());

        mvc.perform(post("/admin/sources/gone/sync"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void listDisclosesPresenceButNeverTheCredentialValue() throws Exception {
        when(sync.sources()).thenReturn(List.of(view("booking")));

        mvc.perform(get("/admin/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serviceKeyConfigured").value(true))
                .andExpect(jsonPath("$.data[0].serviceKeyValue").doesNotExist());
    }

    private static ToolSource source(String key) {
        ToolSource source = new ToolSource();
        source.setSourceKey(key);
        source.setGatewayId("gw1");
        source.setContractUrl("http://h/v3/api-docs");
        source.setOrigin(ToolSource.ORIGIN_ADMIN);
        source.setEnabled(1);
        source.setStatus(ToolSource.STATUS_OK);
        return source;
    }

    private static ContractSyncService.SourceView view(String key) {
        return new ContractSyncService.SourceView(key, "gw1", "http://h/v3/api-docs", null,
                ToolSource.ORIGIN_ADMIN, true, true, ToolSource.STATUS_OK, "hash", 2,
                0, 0, 0, 0, LocalDateTime.now(), LocalDateTime.now(), null);
    }
}
