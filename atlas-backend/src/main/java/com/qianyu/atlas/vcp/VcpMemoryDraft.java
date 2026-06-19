package com.qianyu.atlas.vcp;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vcp_memory_draft")
public class VcpMemoryDraft {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_REVIEW = "review";
    public static final String STATUS_SYNCED = "synced";
    public static final String STATUS_IGNORED = "ignored";
    public static final String STATUS_FAILED = "failed";

    private Long id;
    private Long userId;
    private Long noteId;
    private Long notebookId;
    private String title;
    private String memoryContent;
    private String suggestedDailyNote;
    private String targetDailyNote;
    private String status;
    private String syncedPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
