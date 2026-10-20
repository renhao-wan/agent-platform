package io.github.renhaowan.platform.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 管理端/对话端统一异常出口：异常一律转 ApiResponse 形态（HTTP 状态码语义不变）。
 * 注意：工具执行失败不抛异常也不走这里——失败被包装为文本观察回喂模型（ToolAccessService）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败（@Valid）：取第一个字段错误拼接提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("invalid request");
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NoResourceFoundException e) {
        return ApiResponse.error(404, "not found");
    }

    /** 登录凭证错误（用户不存在/密码不符/账号停用）：401，不泄露具体是哪一项。 */
    @ExceptionHandler(io.github.renhaowan.platform.agent.auth.InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleInvalidCredentials(
            io.github.renhaowan.platform.agent.auth.InvalidCredentialsException e) {
        return ApiResponse.error(401, e.getMessage());
    }

    /** 登录失败次数过多：429，提示稍后再试（不透露账号是否存在）。 */
    @ExceptionHandler(io.github.renhaowan.platform.agent.auth.TooManyLoginAttemptsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Void> handleTooManyAttempts(
            io.github.renhaowan.platform.agent.auth.TooManyLoginAttemptsException e) {
        return ApiResponse.error(429, e.getMessage());
    }

    /** 来源注册表冲突（重复创建、下线配置管理的来源）：409。 */
    @ExceptionHandler(io.github.renhaowan.platform.gateway.registry.SourceConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleSourceConflict(
            io.github.renhaowan.platform.gateway.registry.SourceConflictException e) {
        return ApiResponse.error(409, e.getMessage());
    }

    /** 请求语义不合法（如契约地址不是 http(s) URL）：400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ApiResponse.error(500, "internal error");
    }
}
