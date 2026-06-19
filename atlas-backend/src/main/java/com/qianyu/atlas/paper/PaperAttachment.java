package com.qianyu.atlas.paper;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("paper_attachment")
public class PaperAttachment {
    private Long id;
    private Long userId;
    private Long notebookId;
    private Long noteId;
    private String originalFilename;
    private String storedFilename;
    private String storagePath;
    private String contentType;
    private Long fileSize;
    private String extractedText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
