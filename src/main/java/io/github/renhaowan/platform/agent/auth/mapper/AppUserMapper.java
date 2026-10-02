package io.github.renhaowan.platform.agent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.renhaowan.platform.agent.auth.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * app_user 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供，无需手写 SQL）。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
