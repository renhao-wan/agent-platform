package io.github.renhaowan.platform.agent.auth;

/**
 * 登录凭证错误（用户不存在/密码不符/账号停用）——由 GlobalExceptionHandler 统一翻译为 401。
 * 不继承 Spring Security 类型：项目不引入 Spring Security。
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
