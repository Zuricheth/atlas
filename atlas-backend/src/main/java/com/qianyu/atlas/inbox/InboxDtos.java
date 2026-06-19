package com.qianyu.atlas.inbox;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public class InboxDtos {
    public record InboxRequestView(
            Long id,
            String sourceProject,
            String title,
            String description,
            String status,
            Integer importedCount,
            Integer failedCount,
            String createdAt,
            String updatedAt,
            List<InboxFileView> files
    ) {
    }

    public record InboxFileView(
            Long id,
            String originalFilename,
            String relativePath,
            String contentType,
            Long fileSize,
            String status,
            Long noteId,
            Long libraryItemId,
            String errorMessage
    ) {
    }

    public record AcceptInboxRequest(
            @Positive Long notebookId,
            @Positive Long agentId,
            @Size(max = 300) List<@Positive Long> fileIds,
            @Size(max = 255) String categoryPrefix,
            Boolean includeCurrentContent,
            @Size(max = 32) String importMode,
            Boolean generateNotes
    ) {
    }

    public record PlanInboxRequest(
            @Positive Long agentId,
            @Size(max = 300) List<@Positive Long> fileIds
    ) {
    }

    public record PlanInboxResponse(
            Long notebookId,
            String notebookName,
            String notebookPath,
            String categoryPrefix,
            String summary,
            String readmeFilename,
            String planSource,
            List<String> steps,
            List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        public PlanInboxResponse(Long notebookId, String notebookName, String notebookPath,
                                  String categoryPrefix, String summary, String readmeFilename,
                                  String planSource, List<String> steps) {
            this(notebookId, notebookName, notebookPath, categoryPrefix, summary, readmeFilename,
                    planSource, steps, java.util.Collections.emptyList());
        }
    }

    public record AcceptInboxResponse(
            Long requestId,
            Integer importedCount,
            Integer failedCount,
            String targetNotebookName,
            String targetNotebookPath,
            String operationSummary,
            List<InboxFileView> files
    ) {
    }
}
