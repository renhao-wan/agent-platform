package io.github.renhaowan.platform.agent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上下文裁剪里程碑：被压缩掉的早期对话的摘要存档。
 */
@Data
@TableName("checkpoint")
public class Checkpoint {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionKey;
    private String summary;
    private LocalDateTime createdAt;
}
