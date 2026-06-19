package com.qianyu.atlas.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class TagDtos {
    public record SaveTagRequest(
            @NotBlank @Size(max = 32) String name,
            @Size(max = 16) String color
    ) {
    }

    public record SetNoteTagsRequest(
            @NotNull Long noteId,
            List<Long> tagIds
    ) {
    }
}