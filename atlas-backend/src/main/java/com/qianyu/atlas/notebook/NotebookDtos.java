package com.qianyu.atlas.notebook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class NotebookDtos {
    public record CreateNotebookRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description,
            Long parentId,
            @Size(max = 16) String nodeType
    ) {
    }

    public record RenameNotebookRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 255) String description
    ) {
    }

    public record MergeNotebookRequest(
            @NotNull @Positive Long targetNotebookId
    ) {
    }
}
