package io.github.renhaowan.platform.agent.tool;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;

/**
 * Agent 访问工具层的唯一出口。
 * <p>
 * 当前实现是进程内直调（{@code InProcessToolGateway}）——工具层与 Agent 运行时本来就在
 * 同一个 Spring 应用里，没有理由为一次方法调用套一层网络协议。将来工具层若真的拆成独立部署，
 * 换个实现即可，Agent 循环不用动。
 * <p>
 * 身份只在<b>执行</b>时传入：闸门 2 的鉴权依据是会话绑定的用户角色，不能以无状态单例字段固化。
 * 清单本身与身份无关——无权工具也照常下发给模型，角色要求写在工具描述里。
 */
public interface ToolGateway {

    /** 拉取该网关的全部启用工具（含当前身份无权调用的，权限要求见工具描述）。 */
    List<ToolDefinition> listTools();

    /**
     * 执行工具并返回文本结果。<b>失败也返回含错误说明的文本，不抛异常</b>——
     * 模型看到错误后自行决定重试/换路/放弃，这是 ReAct 循环容错的关键。
     */
    String callTool(String name, String argumentsJson, UserPrincipal user);

    /**
     * 只读授权预判：返回拒绝文本（含 tool error 前缀）表示执行必然失败，empty 表示可继续。
     * <p>
     * 循环只在<b>弹确认之前</b>调用它，用于跳过注定失败的确认；它不替代执行点鉴权。
     */
    default Optional<String> denial(String name, UserPrincipal user) {
        return Optional.empty();
    }
}
