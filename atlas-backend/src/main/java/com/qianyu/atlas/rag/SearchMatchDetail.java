package com.qianyu.atlas.rag;

import java.util.List;

public record SearchMatchDetail(
        Long noteId,
        String title,
        int totalMatches,
        List<SearchMatchExcerpt> excerpts
) {
}
