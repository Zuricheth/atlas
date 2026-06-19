package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_provider")
public class AiProvider {
    private Long id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private Integer enabled;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}