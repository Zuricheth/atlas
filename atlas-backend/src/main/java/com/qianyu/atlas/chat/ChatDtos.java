package com.qianyu.atlas.chat;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class ChatDtos {
    public record RagRequest(
            @NotBlank String question,
            Integer topK,
            Long notebookId
    ) {
    }

    public record Citation(
            Long noteId,
            Integer chunkIndex,
            String content,
            float score
    ) {
    }

    public record RagResponse(
            String answer,
            List<Citation> citations,
            List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        // 兼容旧二元构造
        public RagResponse(String answer, List<Citation> citations) {
            this(answer, citations, java.util.Collections.emptyList());
        }
    }
}