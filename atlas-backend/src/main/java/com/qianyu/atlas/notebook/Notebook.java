package com.qianyu.atlas.notebook;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notebook")
public class Notebook {
    public static final String TYPE_DOMAIN = "domain";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_COLLECTION = "collection";

    private Long id;
    private Long userId;
    private Long parentId;
    private String nodeType;
    private String name;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
