package com.qianyu.atlas.trash;

public class TrashDtos {
    public record TrashItem(
            String kind,
            Long id,
            Long noteId,
            Long notebookId,
            String title,
            String detail,
            String deletedAt,
            String purgeAfter
    ) {
    }
}
