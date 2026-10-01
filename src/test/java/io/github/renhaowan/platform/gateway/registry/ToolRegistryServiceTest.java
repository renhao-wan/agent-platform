package io.github.renhaowan.platform.gateway.registry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.gateway.registry.impl.ToolRegistryServiceImpl;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryServiceTest {

    private final ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
    private final ToolRegistryService service = new ToolRegistryServiceImpl(mapper);

    private ToolDefinition tool(String name) {
        ToolDefinition tool = new ToolDefinition();
        tool.setTenantId(7L);
        tool.setGatewayId("gw1");
        tool.setName(name);
        tool.setHttpMethod("GET");
        tool.setUrlTemplate("http://h/" + name);
        tool.setCreatedAt(LocalDateTime.now());
        return tool;
    }

    @Test
    void upsertInsertsWhenNew() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        service.upsertAll(List.of(tool("search_rooms")));

        verify(mapper).insert(any(ToolDefinition.class));
        verify(mapper, never()).updateById(any(ToolDefinition.class));
    }

    @Test
    void upsertUpdatesWhenNameExists() {
        ToolDefinition existing = tool("search_rooms");
        existing.setId(42L);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        service.upsertAll(List.of(tool("search_rooms")));

        verify(mapper, never()).insert(any(ToolDefinition.class));
        verify(mapper).updateById(any(ToolDefinition.class));
    }
}
