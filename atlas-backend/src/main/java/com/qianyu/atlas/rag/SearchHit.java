package com.qianyu.atlas.rag;

public record SearchHit(
        Long noteId,
        String title,
        String snippet,
        float score,
        String source
) {
}