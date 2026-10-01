package io.github.renhaowan.platform.gateway.tenant;

import java.util.List;
import java.util.Optional;

/**
 * 租户查询与管理。api_key 查询走 60s 本地缓存（MCP 端点每请求都需鉴权，
 * 租户数据量小且变更低频，本地缓存比每次打库/打 Redis 更合适）。
 */
public interface TenantService {

    /** 按 apiKey 查租户（含停用租户，调用方自行校验 status）。 */
    Optional<Tenant> findByApiKey(String apiKey);

    /** 创建租户并签发 apiKey。 */
    Tenant create(String name);

    /** 全量租户列表（管理端）。 */
    List<Tenant> list();
}
