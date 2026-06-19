package com.qianyu.atlas.note;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note_history")
public class NoteHistory {
    private Long id;
    private Long noteId;
    private Long userId;
    private String title;
    private String content;
    private String summary;
    private Integer noteVersion;
    private LocalDateTime createdAt;
}
