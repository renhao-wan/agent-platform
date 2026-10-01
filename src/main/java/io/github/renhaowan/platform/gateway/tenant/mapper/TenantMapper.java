package io.github.renhaowan.platform.gateway.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.renhaowan.platform.gateway.tenant.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * tenant 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供）。
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
