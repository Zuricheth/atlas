package com.qianyu.atlas.library;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("library_item")
public class LibraryItem {
    private Long id;
    private Long userId;
    private Long notebookId;
    private Long noteId;
    private String title;
    private String originalFilename;
    private String storedFilename;
    private String storagePath;
    private String contentType;
    private String fileExt;
    private Long fileSize;
    private String category;
    private String status;
    private String extractedText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
