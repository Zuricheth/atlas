package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note_chunk")
public class NoteChunk {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_FAILED = 2;

    private Long id;
    private Long userId;
    private Long noteId;
    private Integer chunkIndex;
    private String content;
    private String embedding;
    private Integer dim;
    private String model;
    private Long providerId;
    private Integer status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}