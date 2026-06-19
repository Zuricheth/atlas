package com.qianyu.atlas.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class AiAdminDtos {
    public record SaveProviderRequest(
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 255) String baseUrl,
            @Size(max = 255) String apiKey,
            @Size(max = 255) String remark,
            Boolean enabled
    ) {
    }

    public record SaveModelRequest(
            @NotNull Long providerId,
            @NotBlank @Size(max = 16) String kind,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String alias,
            Integer dim,
            Boolean enabled,
            @Size(max = 255) String remark
    ) {
    }

    public record SetActiveRequest(
            @NotBlank String kind,
            @NotNull Long modelId,
            Integer dim
    ) {
    }

    public record ConfigureEmbeddingRequest(
            @NotBlank @Size(max = 255) String baseUrl,
            @Size(max = 255) String apiKey,
            @NotBlank @Size(max = 128) String modelName,
            @NotNull Integer dim
    ) {
    }

    public record TestEmbeddingRequest(
            String text
    ) {
    }

    public record TestEmbeddingResponse(
            String modelName,
            Integer configuredDim,
            Integer actualDim,
            Long providerId
    ) {
    }

    public record SyncNewApiModelsRequest(
            @NotBlank @Size(max = 255) String baseUrl,
            @Size(max = 255) String apiKey
    ) {
    }

    public record SyncNewApiModelsResponse(
            Long providerId,
            Integer imported,
            Integer chatModels,
            Integer embeddingModels
    ) {
    }

    public record SaveAgentRequest(
            Long id,
            @NotBlank @Size(max = 64) String name,
            @NotNull Long modelId,
            @NotBlank String systemPrompt,
            @Size(max = 128) String vcpFolder,
            Boolean enabled,
            Boolean isDefault
    ) {
    }
}
