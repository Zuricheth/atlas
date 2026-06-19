package com.qianyu.atlas.deepwiki;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("deepwiki_page")
public class DeepWikiPage {
    private Long id;
    private Long userId;
    private Long notebookId;
    private Long agentId;
    private String mode;
    private String focus;
    private String focusKey;
    private String title;
    private Integer sourceCount;
    private String markdown;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
