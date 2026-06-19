package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent")
public class AiAgent {
    private Long id;
    private String name;
    private Long modelId;
    private String systemPrompt;
    private String vcpFolder;
    private Integer enabled;
    private Integer isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
