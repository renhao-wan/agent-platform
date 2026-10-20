package io.github.renhaowan.platform.agent.auth;

import java.util.Set;

/**
 * 用户登录：校验凭证并签发 JWT 会话令牌。
 */
public interface AuthService {

    /**
     * @return token 只证明身份；role/permissions 供前端渲染入口与操作差异
     * @throws InvalidCredentialsException 用户名或密码错误 / 账号已停用
     */
    LoginResult login(String username, String password);

    record LoginResult(String token, String role, Set<String> permissions) {
    }
}
