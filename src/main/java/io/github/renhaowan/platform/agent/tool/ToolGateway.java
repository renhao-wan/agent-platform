package io.github.renhaowan.platform.agent.tool;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;

/**
 * Agent 访问工具层的唯一出口。
 * <p>
 * 当前实现是进程内直调（{@code InProcessToolGateway}）——工具层与 Agent 运行时本来就在
 * 同一个 Spring 应用里，没有理由为一次方法调用套一层网络协议。将来工具层若真的拆成独立部署，
 * 换个实现即可，Agent 循环不用动。
 * <p>
 * 身份必须逐调用显式传入：工具可见性（闸门 1）与执行点鉴权（闸门 2）都依据<b>会话绑定的用户角色</b>，
 * 不能以无状态单例字段固化。
 */
public interface ToolGateway {

    /** 拉取当前用户可见的工具清单（已按角色过滤）。 */
    List<ToolDefinition> listTools(UserPrincipal user);

    /**
     * 执行工具并返回文本结果。<b>失败也返回含错误说明的文本，不抛异常</b>——
     * 模型看到错误后自行决定重试/换路/放弃，这是 ReAct 循环容错的关键。
     */
    String callTool(String name, String argumentsJson, UserPrincipal user);
}
