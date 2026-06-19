package com.qianyu.atlas.inbox;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inbox_file")
public class InboxFile {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IMPORTED = "imported";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";

    private Long id;
    private Long requestId;
    private Long userId;
    private String originalFilename;
    private String relativePath;
    private String storedFilename;
    private String storagePath;
    private String contentType;
    private Long fileSize;
    private String status;
    private Long noteId;
    private Long libraryItemId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
