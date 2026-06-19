package com.qianyu.atlas.vcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class VcpDtos {
    public record NotebookView(
            String name,
            Integer fileCount,
            String lastModified
    ) {
    }

    public record NotebookSearchResult(
            String name,
            Integer fileCount,
            String lastModified,
            double score,
            String reason,
            String snippet
    ) {
    }

    public record NotebookRequest(
            @NotBlank @Size(max = 128) String name
    ) {
    }

    public record NotebookFileView(
            String notebook,
            String filename,
            Long size,
            String lastModified
    ) {
    }

    public record FileContentResponse(
            String notebook,
            String filename,
            String content
    ) {
    }

    public record SaveFileRequest(
            @NotNull @Size(max = 1_000_000) String content
    ) {
    }

    public record TransferFilesRequest(
            @NotBlank @Size(max = 128) String sourceNotebook,
            @NotBlank @Size(max = 128) String targetNotebook,
            @NotNull @Size(min = 1, max = 200) List<@NotBlank @Size(max = 255) String> filenames,
            boolean overwrite
    ) {
    }

    public record TransferNotebookRequest(
            @NotBlank @Size(max = 128) String sourceNotebook,
            @NotBlank @Size(max = 128) String targetNotebook,
            boolean overwrite,
            boolean deleteSourceWhenEmpty
    ) {
    }

    public record TransferDraftsRequest(
            @NotBlank @Size(max = 128) String targetNotebook,
            @NotNull @Size(min = 1, max = 500) List<@NotNull Long> draftIds,
            boolean moveSyncedFiles,
            boolean overwrite
    ) {
    }

    public record TransferResult(
            String sourceNotebook,
            String targetNotebook,
            int moved,
            int skipped,
            List<String> messages
    ) {
    }

    public record DraftView(
            Long id,
            Long noteId,
            Long notebookId,
            String title,
            String memoryContent,
            String suggestedDailyNote,
            String targetDailyNote,
            String status,
            String syncedPath,
            String createdAt,
            String updatedAt
    ) {
    }

    public record UpdateDraftRequest(
            @Size(max = 128) String targetDailyNote,
            String memoryContent,
            @Size(max = 24) String status
    ) {
    }

    public record SyncDraftRequest(
            @Size(max = 128) String targetDailyNote
    ) {
    }

    public record SyncDraftResponse(
            Long draftId,
            String notebook,
            String filename,
            String path
    ) {
    }

    public record BatchSuggestRequest(
            Long agentId,
            @NotNull @Size(min = 1, max = 500) List<@NotNull Long> draftIds
    ) {
    }

    public record BatchSuggestResponse(
            String suggestion
    ) {
    }

    public record SuggestDraftTargetRequest(
            Long agentId
    ) {
    }

    public record SuggestDraftTargetResponse(
            DraftView draft,
            String suggestion
    ) {
    }
}
