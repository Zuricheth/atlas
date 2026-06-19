package com.qianyu.atlas.tag;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note_tag")
public class NoteTag {
    private Long id;
    private Long userId;
    private Long noteId;
    private Long tagId;
    private LocalDateTime createdAt;
}