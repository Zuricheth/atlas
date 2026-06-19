package com.qianyu.atlas.rag;

public record SearchMatchExcerpt(
        String field,
        String label,
        String text,
        int matchStart,
        int matchEnd
) {
}
