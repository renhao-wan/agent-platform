package io.github.renhaowan.platform.agent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 对话消息（USER/ASSISTANT/TOOL 三种角色，按 id 升序即为对话时序）。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_TOOL = "TOOL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionKey;
    private String role;
    private String content;
    private String toolName;
    private LocalDateTime createdAt;
}
