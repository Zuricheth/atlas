package com.qianyu.atlas.deepwiki;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class DeepWikiDtos {
    public record GenerateWikiRequest(
            @NotNull Long notebookId,
            Long agentId,
            @Size(max = 32) String mode,
            @Size(max = 128) String focus
    ) {
    }

    public record GenerateWikiResponse(
            Long notebookId,
            String title,
            String mode,
            String focus,
            Integer sourceCount,
            String markdown,
            String updatedAt,
            Boolean stale,
            java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        // 兼容旧 8 参构造
        public GenerateWikiResponse(Long notebookId, String title, String mode, String focus,
                                     Integer sourceCount, String markdown, String updatedAt, Boolean stale) {
            this(notebookId, title, mode, focus, sourceCount, markdown, updatedAt, stale, java.util.Collections.emptyList());
        }
        public GenerateWikiResponse withAiTrace(java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> trace) {
            return new GenerateWikiResponse(notebookId, title, mode, focus, sourceCount, markdown, updatedAt, stale, trace);
        }
    }
}
