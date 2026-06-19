package com.qianyu.atlas.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class AssetDtos {
    public record SpaceBucket(
            String key,
            String label,
            long count,
            long bytes,
            boolean reclaimable,
            String description
    ) {
    }

    public record FileTypeStat(
            String label,
            long count,
            long bytes
    ) {
    }

    public record SpaceSummary(
            long totalBytes,
            long reclaimableBytes,
            long activeFiles,
            long trashItems,
            long imageFiles,
            List<SpaceBucket> buckets,
            List<FileTypeStat> fileTypes
    ) {
    }

    public record AssetItem(
            String key,
            String source,
            Long id,
            Long noteId,
            Long notebookId,
            String notebookName,
            String notebookPath,
            String title,
            String originalFilename,
            String fileUrl,
            String contentType,
            String fileExt,
            String typeLabel,
            long fileSize,
            String category,
            boolean image,
            String status,
            String createdAt,
            String updatedAt
    ) {
    }

    public record BulkAssetRequest(
            @Size(max = 500) List<@NotBlank @Size(max = 128) String> keys
    ) {
    }

    public record BulkAssetResponse(
            int count
    ) {
    }
}
