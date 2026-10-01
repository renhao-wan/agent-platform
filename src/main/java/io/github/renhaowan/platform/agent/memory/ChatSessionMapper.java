package io.github.renhaowan.platform.agent.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * chat_session 表访问接口（CRUD 由 MyBatis-Plus BaseMapper 提供，无需手写 SQL）。
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
