package com.qianyu.atlas.notebook;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.notebook.NotebookDtos.CreateNotebookRequest;
import com.qianyu.atlas.notebook.NotebookDtos.MergeNotebookRequest;
import com.qianyu.atlas.notebook.NotebookDtos.RenameNotebookRequest;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notebooks")
@Validated
public class NotebookController {
    private final NotebookService notebookService;

    public NotebookController(NotebookService notebookService) {
        this.notebookService = notebookService;
    }

    @PostMapping
    public ApiResponse<Notebook> create(@AuthenticationPrincipal CurrentUser currentUser,
                                        @Valid @RequestBody CreateNotebookRequest request) {
        return ApiResponse.ok(notebookService.create(currentUser.id(), request));
    }

    @GetMapping
    public ApiResponse<List<Notebook>> listMine(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(notebookService.listMine(currentUser.id()));
    }

    @PutMapping("/{notebookId}")
    public ApiResponse<Notebook> rename(@AuthenticationPrincipal CurrentUser currentUser,
                                        @PathVariable @Positive Long notebookId,
                                        @Valid @RequestBody RenameNotebookRequest request) {
        return ApiResponse.ok(notebookService.rename(currentUser.id(), notebookId, request));
    }

    @PostMapping("/{sourceNotebookId}/merge")
    public ApiResponse<Notebook> merge(@AuthenticationPrincipal CurrentUser currentUser,
                                       @PathVariable @Positive Long sourceNotebookId,
                                       @Valid @RequestBody MergeNotebookRequest request) {
        return ApiResponse.ok(notebookService.mergeInto(currentUser.id(), sourceNotebookId, request.targetNotebookId()));
    }

    @DeleteMapping("/{notebookId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CurrentUser currentUser,
                                    @PathVariable @Positive Long notebookId) {
        notebookService.delete(currentUser.id(), notebookId);
        return ApiResponse.ok();
    }
}
