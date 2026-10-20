package io.github.renhaowan.platform.gateway.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工具访问的唯一入口：清单组装 + 执行点鉴权 + 泛化转发。
 * <p>
 * 闸门 1（清单组装）：下发该网关<b>全部启用</b>工具，不按权限裁剪。权限要求写进工具描述，
 * 模型因此知道"这个能力存在、但当前身份不够"，被拒时能给出准确答复；若在这里就裁掉，
 * 模型只会答"我没有这个功能"，把权限问题说成能力问题。
 * <p>
 * 闸门 2（执行点 ★ 安全边界）：模型可能幻觉出不存在的工具名，也可能被提示注入诱导去调越权工具，
 * 因此执行前必须按<b>请求时从 RBAC 表加载的权限集合</b>再校验一次——校验依据是代码里的身份，
 * 不是模型说了什么，也不是客户端 token 里写的内容。
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

    /** 闸门 1：下发该网关全部启用工具。权限要求不在此处裁剪，改由工具描述向模型披露。 */
    public List<ToolDefinition> listForModel(String gatewayId) {
        return registryService.listEnabled(gatewayId);
    }

    /**
     * 只读授权预判：返回拒绝文本表示"执行必然失败"，empty 表示可以继续。
     * <p>
     * 供 ReAct 循环在<b>弹确认之前</b>使用：无权限用户不该先点一次注定失败的确认。
     * 预判只能跳过确认，不能替代 {@link #invoke} 里的执行点鉴权——模型幻觉或被注入后
     * 仍可能绕过预判直接调工具，真正的安全边界永远在执行点。
     */
    public Optional<String> denial(String gatewayId, UserPrincipal user, String name) {
        Optional<ToolDefinition> tool = registryService.find(gatewayId, name);
        if (tool.isEmpty()) {
            return Optional.of("unknown tool: " + name);
        }
        return permissionDenial(tool.get(), user);
    }

    /**
     * 权限判定：优先看契约声明的权限码；历史上只写了 required_role 的行回退为
     * {@code role:<ROLE>} 语义，保持旧数据可解释。
     */
    private static Optional<String> permissionDenial(ToolDefinition tool, UserPrincipal user) {
        String requiredPermission = tool.getRequiredPermission();
        if (requiredPermission != null && !requiredPermission.isBlank()) {
            if (user == null || !user.hasPermission(requiredPermission)) {
                return Optional.of("权限不足：需要 " + requiredPermission + " 权限");
            }
            return Optional.empty();
        }
        String requiredRole = tool.getRequiredRole();
        if (requiredRole != null && !requiredRole.isBlank()) {
            boolean roleMatches = user != null
                    && (requiredRole.equals(user.role()) || user.hasPermission("role:" + requiredRole));
            if (!roleMatches) {
                return Optional.of("权限不足：需要 " + requiredRole + " 角色");
            }
        }
        return Optional.empty();
    }

    /**
     * 闸门 2，通过后泛化转发。任何失败都包装成可读文本回喂模型。
     * <p>
     * {@code user} 取自请求上下文：权限集合用于闸门 2 判定，uid 作为
     * {@code X-On-Behalf-Of-User} 透传给业务系统，使数据按最终用户归属。
     */
    public ToolCallResult invoke(String gatewayId, UserPrincipal user, String name, String argsJson) {
        Optional<ToolDefinition> tool = registryService.find(gatewayId, name);
        if (tool.isEmpty()) {
            return new ToolCallResult(true, "unknown tool: " + name);
        }

        Optional<String> denial = permissionDenial(tool.get(), user);
        if (denial.isPresent()) {
            return new ToolCallResult(true, denial.get());
        }

        JsonNode arguments;
        try {
            arguments = (argsJson == null || argsJson.isBlank()) ? null : MAPPER.readTree(argsJson);
        } catch (Exception e) {
            // 参数不是合法 JSON：如实回喂，模型可自行修正后重试（自愈路径）
            return new ToolCallResult(true, "参数解析失败，需要合法 JSON 对象");
        }

        GenericHttpForwarder.ForwardResult result =
                forwarder.forward(tool.get(), arguments, user == null ? null : user.uid());
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
