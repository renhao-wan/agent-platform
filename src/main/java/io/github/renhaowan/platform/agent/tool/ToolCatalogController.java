package io.github.renhaowan.platform.agent.tool;

import io.github.renhaowan.platform.agent.auth.UserContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具目录：当前用户可见的工具清单（已过闸门 1），供控制台据此生成示例指令。
 * <p>
 * 示例指令不写死在页面里——写死就意味着"平台代码里躺着某个业务系统的词汇"，
 * 换个业务系统接入，示例就成了错的。改为运行时按可见工具生成：接什么系统，示例就是什么。
 * <p>
 * 只出 {@code name} / {@code description}：{@code auth_header_value} 是转发用的服务凭证，
 * 不出这个边界。
 */
@RestController
@RequiredArgsConstructor
public class ToolCatalogController {

    public record ToolBrief(String name, String description) {
    }

    private final ToolGateway toolGateway;

    @GetMapping("/api/v1/tools")
    public List<ToolBrief> list() {
        return toolGateway.listTools(UserContext.get()).stream()
                .map(tool -> new ToolBrief(tool.getName(), tool.getDescription()))
                .toList();
    }
}
