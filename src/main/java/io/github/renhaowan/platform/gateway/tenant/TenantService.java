package io.github.renhaowan.platform.gateway.tenant;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 租户查询与管理。api_key 查询走 60s 本地缓存（MCP 端点每请求都需鉴权，
 * 租户数据量小且变更低频，本地缓存比每次打库/打 Redis 更合适）。
 */
@Service
public class TenantService {

    private static final long CACHE_TTL_MS = 60_000;

    private final TenantMapper tenantMapper;
    private final Map<String, CachedTenant> cacheByKey = new ConcurrentHashMap<>();

    public TenantService(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    public Optional<Tenant> findByApiKey(String apiKey) {
        long now = System.currentTimeMillis();
        CachedTenant cached = cacheByKey.get(apiKey);
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MS) {
            return Optional.ofNullable(cached.tenant());
        }
        Tenant tenant = tenantMapper.selectOne(new QueryWrapper<Tenant>().eq("api_key", apiKey));
        cacheByKey.put(apiKey, new CachedTenant(tenant, now));
        return Optional.ofNullable(tenant);
    }

    public Tenant create(String name) {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setApiKey(UUID.randomUUID().toString());
        tenant.setStatus(Tenant.STATUS_ENABLED);
        tenant.setCreatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        return tenant;
    }

    public List<Tenant> list() {
        return tenantMapper.selectList(null);
    }

    private record CachedTenant(Tenant tenant, long loadedAt) {
    }
}
