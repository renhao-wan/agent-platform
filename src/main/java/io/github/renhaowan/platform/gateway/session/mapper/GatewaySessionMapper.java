package io.github.renhaowan.platform.gateway.session.mapper;

import io.github.renhaowan.platform.gateway.session.entity.GatewaySession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * gateway_session 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供）。
 */
@Mapper
public interface GatewaySessionMapper extends BaseMapper<GatewaySession> {
}
