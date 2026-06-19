package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_active")
public class AiActiveModel {
    public static final String SCOPE_SYSTEM = "system";

    private Long id;
    private String scope;
    private String kind;
    private Long modelId;
    private LocalDateTime updatedAt;
}