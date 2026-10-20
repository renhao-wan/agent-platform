package io.github.renhaowan.platform.agent.confirm;

import io.github.renhaowan.platform.agent.auth.UserContext;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
        // confirmToken 只是随机串，必须再核对发起者：否则任何登录用户拿到 token
        // 就能批准/拒绝他人的敏感操作
        ConfirmManager.PendingCall pending = confirmManager.pendingOf(request.confirmToken());
        UserPrincipal me = UserContext.get();
        if (pending == null || me == null || pending.userId() != me.uid()) {
            return new ConfirmResult(false);
        }
        return new ConfirmResult(confirmManager.complete(request.confirmToken(), request.approved()));
    }
}
