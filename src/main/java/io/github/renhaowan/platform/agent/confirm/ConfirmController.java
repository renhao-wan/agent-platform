package io.github.renhaowan.platform.agent.confirm;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感操作人工确认入口：SSE confirm_request 事件携带 confirmToken，
 * 前端用户点击后调用本接口恢复 Agent 循环。
 */
@RestController
@RequestMapping("/api/v1/confirm")
public class ConfirmController {

    public record ConfirmRequest(@NotNull String confirmToken, boolean approved) {
    }

    public record ConfirmResult(boolean completed) {
    }

    private final ConfirmManager confirmManager;

    public ConfirmController(ConfirmManager confirmManager) {
        this.confirmManager = confirmManager;
    }

    @PostMapping
    public ConfirmResult confirm(@RequestBody @Valid ConfirmRequest request) {
        boolean completed = confirmManager.complete(request.confirmToken(), request.approved());
        return new ConfirmResult(completed);
    }
}
