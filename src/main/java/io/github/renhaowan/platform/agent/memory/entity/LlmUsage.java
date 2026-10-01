package io.github.renhaowan.platform.agent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * LLM 用量打点行（prompt/completion tokens，按 phase 区分调用环节）。
 */
@Data
@TableName("llm_usage")
public class LlmUsage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionKey;
    private String phase;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private LocalDateTime createdAt;
}
