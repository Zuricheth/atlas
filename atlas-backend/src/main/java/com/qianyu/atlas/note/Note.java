package com.qianyu.atlas.note;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note")
public class Note {
    private Long id;
    private Long userId;
    private Long notebookId;
    private String title;
    private String content;
    private String summary;
    private String searchText;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}