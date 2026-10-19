package io.github.renhaowan.platform.gateway.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工具访问的唯一入口：清单组装 + 执行点鉴权 + 泛化转发。
 * <p>
 * 闸门 1（清单组装）：下发该网关<b>全部启用</b>工具，不按角色裁剪。角色要求写进工具描述，
 * 模型因此知道"这个能力存在、但当前身份不够"，被拒时能给出准确答复；若在这里就裁掉，
 * 模型只会答"我没有这个功能"，把权限问题说成能力问题。
 * <p>
 * 闸门 2（执行点 ★ 安全边界）：模型可能幻觉出不存在的工具名，也可能被提示注入诱导去调越权工具，
 * 因此执行前必须按<b>会话绑定的用户角色</b>再校验一次——校验依据是代码里的身份，不是模型说了什么。
 * <p>
 * 两道闸门的共同约定：<b>拒绝一律以文本结果返回（isError=true），不抛异常</b>。
 * 这是 ReAct 循环能"看到失败并自己决定下一步"的前提，也是本项目的核心不变量之一。
 */
@Service
@RequiredArgsConstructor
public class ToolAccessService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistryService registryService;
    private final GenericHttpForwarder forwarder;

    /** 闸门 1：下发该网关全部启用工具。角色要求不在此处裁剪，改由工具描述向模型披露。 */
    public List<ToolDefinition> listForModel(String gatewayId) {
        return registryService.listEnabled(gatewayId);
    }

    /** 闸门 2，通过后泛化转发。任何失败都包装成可读文本回喂模型。 */
    public ToolCallResult invoke(String gatewayId, String userRole, String name, String argsJson) {
        Optional<ToolDefinition> tool = registryService.find(gatewayId, name);
        if (tool.isEmpty()) {
            return new ToolCallResult(true, "unknown tool: " + name);
        }

        String requiredRole = tool.get().getRequiredRole();
        if (requiredRole != null && !requiredRole.equals(userRole)) {
            return new ToolCallResult(true, "权限不足：需要 " + requiredRole + " 角色");
        }

        JsonNode arguments;
        try {
            arguments = (argsJson == null || argsJson.isBlank()) ? null : MAPPER.readTree(argsJson);
        } catch (Exception e) {
            // 参数不是合法 JSON：如实回喂，模型可自行修正后重试（自愈路径）
            return new ToolCallResult(true, "参数解析失败，需要合法 JSON 对象");
        }

        GenericHttpForwarder.ForwardResult result = forwarder.forward(tool.get(), arguments);
        if (result.success()) {
            return new ToolCallResult(false, result.body());
        }
        return new ToolCallResult(true,
                result.error() == null ? "tool execution failed" : result.error());
    }

    /** 调用结果：isError=true 时 text 是失败原因，模型据此决定重试还是转述用户。 */
    public record ToolCallResult(boolean isError, String text) {
    }
}
