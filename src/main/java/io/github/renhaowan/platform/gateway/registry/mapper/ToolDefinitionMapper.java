package io.github.renhaowan.platform.gateway.registry.mapper;

import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * tool_definition 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供）。
 */
@Mapper
public interface ToolDefinitionMapper extends BaseMapper<ToolDefinition> {
}
