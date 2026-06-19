package com.qianyu.atlas.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class NoteDtos {
    public record SaveNoteRequest(
            @NotNull Long notebookId,
            @NotBlank @Size(max = 128) String title,
            @NotBlank String content,
            @Size(max = 512) String summary
    ) {
    }

    public record SearchNoteRequest(
            @NotBlank String keyword,
            Integer limit
    ) {
    }

    public record NoteFileLink(
            String label,
            String url,
            String contentType,
            String source
    ) {
    }

    public record NoteDetail(
            Long id,
            Long userId,
            Long notebookId,
            String title,
            String content,
            String summary,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt,
            java.util.List<NoteFileLink> fileLinks
    ) {
    }

    public record AgentNoteRequest(
            Long agentId,
            Boolean includeCurrentContent
    ) {
    }

    public record AgentNoteResponse(
            Long agentId,
            String content,
            java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        public AgentNoteResponse(Long agentId, String content) {
            this(agentId, content, java.util.Collections.emptyList());
        }
    }

    public record NoteHistoryItem(
            Long id,
            Integer noteVersion,
            String title,
            String summaryExcerpt,
            java.time.LocalDateTime createdAt
    ) {
    }
}
