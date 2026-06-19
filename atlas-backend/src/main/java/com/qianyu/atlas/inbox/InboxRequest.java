package com.qianyu.atlas.inbox;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inbox_request")
public class InboxRequest {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IMPORTED = "imported";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_REJECTED = "rejected";

    private Long id;
    private Long userId;
    private String sourceProject;
    private String title;
    private String description;
    private String status;
    private Integer importedCount;
    private Integer failedCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
