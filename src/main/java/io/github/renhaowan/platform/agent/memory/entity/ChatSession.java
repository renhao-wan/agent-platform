package io.github.renhaowan.platform.agent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Agent 对话会话（sessionKey 为业务主键，消息历史挂在其下）。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionKey;
    private String title;
    private LocalDateTime createdAt;
}
