package com.qianyu.atlas.library;

import java.time.LocalDateTime;

public class LibraryDtos {
    public record ImportLibraryItemResponse(
            Long itemId,
            Long noteId,
            Long notebookId,
            String notebookName,
            String notebookPath,
            String title,
            String category,
            java.util.List<String> tags,
            String originalFilename,
            String fileUrl,
            java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        public ImportLibraryItemResponse(Long itemId, Long noteId, Long notebookId, String notebookName,
                                          String notebookPath, String title, String category,
                                          java.util.List<String> tags, String originalFilename, String fileUrl) {
            this(itemId, noteId, notebookId, notebookName, notebookPath, title, category, tags,
                    originalFilename, fileUrl, java.util.Collections.emptyList());
        }
        public ImportLibraryItemResponse withAiTrace(java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> trace) {
            return new ImportLibraryItemResponse(itemId, noteId, notebookId, notebookName, notebookPath,
                    title, category, tags, originalFilename, fileUrl, trace);
        }
    }

    public record LibraryItemView(
            Long id,
            Long noteId,
            String title,
            String originalFilename,
            String contentType,
            String fileExt,
            Long fileSize,
            String category,
            String status,
            String fileUrl,
            Long notebookId,
            String notebookPath,
            LocalDateTime createdAt
    ) {
    }

    public record FolderImportResponse(
            String rootName,
            String planner,
            java.util.List<String> tree,
            java.util.List<ImportLibraryItemResponse> files,
            java.util.List<com.qianyu.atlas.ai.AiTracer.AiCall> aiTrace
    ) {
        public FolderImportResponse(String rootName, String planner,
                                     java.util.List<String> tree,
                                     java.util.List<ImportLibraryItemResponse> files) {
            this(rootName, planner, tree, files, java.util.Collections.emptyList());
        }
    }

    public record FolderPlanRequest(
            java.util.List<FolderPlanInput> files,
            String correction,
            FolderPlanResponse previousPlan
    ) {
    }

    public record FolderPlanInput(
            String path,
            Long size,
            String type,
            String text
    ) {
    }

    public record FolderPlanResponse(
            String rootName,
            String planner,
            String databaseSummary,
            java.util.List<String> tree,
            java.util.List<FolderPlannedFile> files
    ) {
    }

    public record FolderPlannedFile(
            String path,
            String domainName,
            String projectName,
            String collectionName,
            String notebookName,
            String notebookDescription,
            String categoryPath,
            String title,
            java.util.List<String> tags,
            boolean uncertain,
            String reason
    ) {
    }
}
