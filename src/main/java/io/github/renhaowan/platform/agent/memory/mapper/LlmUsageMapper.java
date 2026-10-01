package io.github.renhaowan.platform.agent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.renhaowan.platform.agent.memory.entity.LlmUsage;
import org.apache.ibatis.annotations.Mapper;

/**
 * llm_usage 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供）。
 */
@Mapper
public interface LlmUsageMapper extends BaseMapper<LlmUsage> {
}
