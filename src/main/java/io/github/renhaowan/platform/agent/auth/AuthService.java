package io.github.renhaowan.platform.agent.auth;

/**
 * 用户登录：校验凭证并签发 JWT 会话令牌。
 */
public interface AuthService {

    /**
     * @return 含 token 与角色（前端据 role 渲染权限差异；token 放 Authorization: Bearer 头）
     * @throws InvalidCredentialsException 用户名或密码错误 / 账号已停用
     */
    LoginResult login(String username, String password);

    record LoginResult(String token, String role) {
    }
}
