package com.qianyu.atlas.rag;

public record VectorSearchResult(
        Long chunkId,
        Long noteId,
        Integer chunkIndex,
        String content,
        float score
) {
}