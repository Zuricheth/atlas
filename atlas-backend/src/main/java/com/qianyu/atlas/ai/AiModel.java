package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model")
public class AiModel {
    public static final String KIND_CHAT = "chat";
    public static final String KIND_EMBEDDING = "embedding";

    private Long id;
    private Long providerId;
    private String kind;
    private String name;
    private String alias;
    private Integer dim;
    private Integer enabled;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}