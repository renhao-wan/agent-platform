package io.github.renhaowan.platform.gateway.registry;

/** 来源注册表的状态冲突（重复创建、下线由配置管理的来源）：由全局异常处理映射为 409。 */
public class SourceConflictException extends RuntimeException {

    public SourceConflictException(String message) {
        super(message);
    }
}
