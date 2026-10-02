package io.github.renhaowan.platform.agent.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户登录入口：POST /api/v1/auth/login {username,password} → {token, role}。
 * 登录端点本身不拦 JWT（JwtAuthFilter 只覆盖 /api/v1/chat 与 /api/v1/confirm）。
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
