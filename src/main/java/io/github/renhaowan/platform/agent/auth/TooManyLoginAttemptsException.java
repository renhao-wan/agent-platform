package io.github.renhaowan.platform.agent.auth;

/** 同一账号短时间内失败次数过多——由 GlobalExceptionHandler 翻译为 429。 */
public class TooManyLoginAttemptsException extends RuntimeException {

    public TooManyLoginAttemptsException(String message) {
        super(message);
    }
}
