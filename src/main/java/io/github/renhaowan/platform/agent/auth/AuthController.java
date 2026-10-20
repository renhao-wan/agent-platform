package io.github.renhaowan.platform.agent.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户登录入口：POST /api/v1/auth/login {username,password} → {token, role, permissions}。
 * <p>登录、静态页与 Actuator 之外，其余端点都要求 Bearer JWT：{@code /api/v1/chat}、
 * {@code /api/v1/confirm}、{@code /api/v1/tools}、{@code /api/v1/sessions/**} 与
 * {@code /admin/**}（见 {@link JwtAuthFilter#requiresAuth}）。
 */
@RestController
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/v1/auth/login")
    public AuthService.LoginResult login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request.username(), request.password());
    }
}
