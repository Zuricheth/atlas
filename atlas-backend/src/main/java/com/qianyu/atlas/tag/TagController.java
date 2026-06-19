package com.qianyu.atlas.tag;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.security.CurrentUser;
import com.qianyu.atlas.tag.TagDtos.SaveTagRequest;
import com.qianyu.atlas.tag.TagDtos.SetNoteTagsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@Validated
public class TagController {
    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @PostMapping
    public ApiResponse<Tag> create(@AuthenticationPrincipal CurrentUser currentUser,
                                  @Valid @RequestBody SaveTagRequest request) {
        return ApiResponse.ok(tagService.create(currentUser.id(), request));
    }

    @GetMapping
    public ApiResponse<List<Tag>> listMine(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(tagService.listMine(currentUser.id()));
    }

    @PutMapping("/note")
    public ApiResponse<List<Tag>> setNoteTags(@AuthenticationPrincipal CurrentUser currentUser,
                                               @Valid @RequestBody SetNoteTagsRequest request) {
        return ApiResponse.ok(tagService.setNoteTags(currentUser.id(), request));
    }

    @GetMapping("/note/{noteId}")
    public ApiResponse<List<Tag>> listByNote(@AuthenticationPrincipal CurrentUser currentUser,
                                             @PathVariable @Positive Long noteId) {
        return ApiResponse.ok(tagService.listByNote(currentUser.id(), noteId));
    }
}
