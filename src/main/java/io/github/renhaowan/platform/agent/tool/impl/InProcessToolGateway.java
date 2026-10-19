package io.github.renhaowan.platform.agent.tool.impl;

import io.github.renhaowan.platform.agent.AgentProperties;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.tool.ToolGateway;
import io.github.renhaowan.platform.gateway.registry.ToolAccessService;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 进程内实现：直接把调用交给 {@link ToolAccessService}，不过协议、不过网络。
 * <p>
 * 网关 id 决定这次对话用哪一套工具（同一平台可挂多组工具），从配置读取。
 */
@Service
@RequiredArgsConstructor
public class InProcessToolGateway implements ToolGateway {

    /** 失败文本前缀：与网关侧约定一致，模型据此识别"这一步没成功"。 */
    private static final String ERROR_PREFIX = "tool error: ";

    private final ToolAccessService toolAccessService;
    private final AgentProperties properties;

    @Override
    public List<ToolDefinition> listTools() {
        return toolAccessService.listForModel(properties.getGatewayId());
    }

    @Override
    public String callTool(String name, String argumentsJson, UserPrincipal user) {
        ToolAccessService.ToolCallResult result =
                toolAccessService.invoke(properties.getGatewayId(), roleOf(user), name, argumentsJson);
        return result.isError() ? ERROR_PREFIX + result.text() : result.text();
    }

    private static String roleOf(UserPrincipal user) {
        return user == null ? null : user.role();
    }
}
