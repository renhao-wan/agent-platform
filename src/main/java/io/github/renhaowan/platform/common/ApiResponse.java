package io.github.renhaowan.platform.common;

/**
 * 管理端统一响应体：{"code":0,"message":"ok","data":...}。
 * 约定 code=0 为成功；非 0 时取值与 HTTP 状态码一致（400/404/429/500...），message 为人话描述。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static final int OK_CODE = 0;
    public static final String OK_MESSAGE = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK_CODE, OK_MESSAGE, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(OK_CODE, OK_MESSAGE, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
