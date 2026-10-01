package io.github.renhaowan.platform.agent.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * checkpoint 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供）。
 */
@Mapper
public interface CheckpointMapper extends BaseMapper<Checkpoint> {
}
