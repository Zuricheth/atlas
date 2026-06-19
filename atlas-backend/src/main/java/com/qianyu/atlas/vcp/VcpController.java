package com.qianyu.atlas.vcp;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.security.CurrentUser;
import com.qianyu.atlas.vcp.VcpDtos.BatchSuggestRequest;
import com.qianyu.atlas.vcp.VcpDtos.BatchSuggestResponse;
import com.qianyu.atlas.vcp.VcpDtos.DraftView;
import com.qianyu.atlas.vcp.VcpDtos.FileContentResponse;
import com.qianyu.atlas.vcp.VcpDtos.NotebookFileView;
import com.qianyu.atlas.vcp.VcpDtos.NotebookRequest;
import com.qianyu.atlas.vcp.VcpDtos.NotebookSearchResult;
import com.qianyu.atlas.vcp.VcpDtos.NotebookView;
import com.qianyu.atlas.vcp.VcpDtos.SaveFileRequest;
import com.qianyu.atlas.vcp.VcpDtos.SuggestDraftTargetRequest;
import com.qianyu.atlas.vcp.VcpDtos.SuggestDraftTargetResponse;
import com.qianyu.atlas.vcp.VcpDtos.SyncDraftRequest;
import com.qianyu.atlas.vcp.VcpDtos.SyncDraftResponse;
import com.qianyu.atlas.vcp.VcpDtos.TransferDraftsRequest;
import com.qianyu.atlas.vcp.VcpDtos.TransferFilesRequest;
import com.qianyu.atlas.vcp.VcpDtos.TransferNotebookRequest;
import com.qianyu.atlas.vcp.VcpDtos.TransferResult;
import com.qianyu.atlas.vcp.VcpDtos.UpdateDraftRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vcp")
@Validated
public class VcpController {
    private final VcpNotebookService notebookService;
    private final VcpMemoryDraftService draftService;

    public VcpController(VcpNotebookService notebookService, VcpMemoryDraftService draftService) {
        this.notebookService = notebookService;
        this.draftService = draftService;
    }

    @GetMapping("/notebooks")
    public ApiResponse<List<NotebookView>> listNotebooks(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(notebookService.listNotebooks(currentUser.id()));
    }

    @GetMapping("/notebooks/search")
    public ApiResponse<List<NotebookSearchResult>> searchNotebooks(@AuthenticationPrincipal CurrentUser currentUser,
                                                                   @RequestParam(required = false) @Size(max = 128) String q,
                                                                   @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(notebookService.searchNotebooks(currentUser.id(), q, limit));
    }

    @PostMapping("/notebooks")
    public ApiResponse<NotebookView> createNotebook(@AuthenticationPrincipal CurrentUser currentUser,
                                                    @Valid @RequestBody NotebookRequest request) {
        return ApiResponse.ok(notebookService.createNotebook(currentUser.id(), request.name()));
    }

    @PutMapping("/notebooks/{name}")
    public ApiResponse<NotebookView> renameNotebook(@AuthenticationPrincipal CurrentUser currentUser,
                                                    @PathVariable @NotBlank @Size(max = 128) String name,
                                                    @Valid @RequestBody NotebookRequest request) {
        return ApiResponse.ok(notebookService.renameNotebook(currentUser.id(), name, request.name()));
    }

    @DeleteMapping("/notebooks/{name}")
    public ApiResponse<Void> deleteNotebook(@AuthenticationPrincipal CurrentUser currentUser,
                                            @PathVariable @NotBlank @Size(max = 128) String name,
                                            @RequestParam(defaultValue = "false") boolean force) {
        notebookService.deleteNotebook(currentUser.id(), name, force);
        return ApiResponse.ok();
    }

    @GetMapping("/notebooks/{name}/files")
    public ApiResponse<List<NotebookFileView>> listFiles(@AuthenticationPrincipal CurrentUser currentUser,
                                                         @PathVariable @NotBlank @Size(max = 128) String name) {
        return ApiResponse.ok(notebookService.listFiles(currentUser.id(), name));
    }

    @GetMapping("/files")
    public ApiResponse<FileContentResponse> readFile(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @RequestParam @NotBlank @Size(max = 128) String notebook,
                                                     @RequestParam @NotBlank @Size(max = 255) String file) {
        return ApiResponse.ok(notebookService.readFile(currentUser.id(), notebook, file));
    }

    @PutMapping("/files")
    public ApiResponse<FileContentResponse> saveFile(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @RequestParam @NotBlank @Size(max = 128) String notebook,
                                                     @RequestParam @NotBlank @Size(max = 255) String file,
                                                     @Valid @RequestBody SaveFileRequest request) {
        return ApiResponse.ok(notebookService.saveFile(currentUser.id(), notebook, file, request.content()));
    }

    @DeleteMapping("/files")
    public ApiResponse<Void> deleteFile(@AuthenticationPrincipal CurrentUser currentUser,
                                        @RequestParam @NotBlank @Size(max = 128) String notebook,
                                        @RequestParam @NotBlank @Size(max = 255) String file) {
        notebookService.deleteFile(currentUser.id(), notebook, file);
        return ApiResponse.ok();
    }

    @PostMapping("/files/transfer")
    public ApiResponse<TransferResult> transferFiles(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @Valid @RequestBody TransferFilesRequest request) {
        return ApiResponse.ok(notebookService.transferFiles(
                currentUser.id(),
                request.sourceNotebook(),
                request.targetNotebook(),
                request.filenames(),
                request.overwrite()
        ));
    }

    @PostMapping("/notebooks/transfer-contents")
    public ApiResponse<TransferResult> transferNotebookContents(@AuthenticationPrincipal CurrentUser currentUser,
                                                                @Valid @RequestBody TransferNotebookRequest request) {
        return ApiResponse.ok(notebookService.transferNotebookContents(
                currentUser.id(),
                request.sourceNotebook(),
                request.targetNotebook(),
                request.overwrite(),
                request.deleteSourceWhenEmpty()
        ));
    }

    @GetMapping("/drafts")
    public ApiResponse<List<DraftView>> listDrafts(@AuthenticationPrincipal CurrentUser currentUser,
                                                   @RequestParam(required = false) @Size(max = 24) String status) {
        return ApiResponse.ok(draftService.list(currentUser.id(), status));
    }

    @PutMapping("/drafts/{draftId}")
    public ApiResponse<DraftView> updateDraft(@AuthenticationPrincipal CurrentUser currentUser,
                                              @PathVariable @Positive Long draftId,
                                              @Valid @RequestBody UpdateDraftRequest request) {
        return ApiResponse.ok(draftService.update(currentUser.id(), draftId, request));
    }

    @PostMapping("/drafts/{draftId}/sync")
    public ApiResponse<SyncDraftResponse> syncDraft(@AuthenticationPrincipal CurrentUser currentUser,
                                                    @PathVariable @Positive Long draftId,
                                                    @Valid @RequestBody(required = false) SyncDraftRequest request) {
        String target = request == null ? null : request.targetDailyNote();
        return ApiResponse.ok(draftService.sync(currentUser.id(), draftId, target));
    }

    @DeleteMapping("/drafts/{draftId}")
    public ApiResponse<Void> deleteDraft(@AuthenticationPrincipal CurrentUser currentUser,
                                         @PathVariable @Positive Long draftId) {
        draftService.delete(currentUser.id(), draftId);
        return ApiResponse.ok();
    }

    @PostMapping("/drafts/batch-suggest")
    public ApiResponse<BatchSuggestResponse> suggest(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @Valid @RequestBody BatchSuggestRequest request) {
        return ApiResponse.ok(draftService.suggest(currentUser.id(), request));
    }

    @PostMapping("/drafts/{draftId}/suggest-target")
    public ApiResponse<SuggestDraftTargetResponse> suggestTarget(@AuthenticationPrincipal CurrentUser currentUser,
                                                                 @PathVariable @Positive Long draftId,
                                                                 @Valid @RequestBody(required = false) SuggestDraftTargetRequest request) {
        Long agentId = request == null ? null : request.agentId();
        return ApiResponse.ok(draftService.suggestTarget(currentUser.id(), draftId, agentId));
    }

    @PostMapping("/drafts/transfer")
    public ApiResponse<TransferResult> transferDrafts(@AuthenticationPrincipal CurrentUser currentUser,
                                                      @Valid @RequestBody TransferDraftsRequest request) {
        return ApiResponse.ok(draftService.transfer(currentUser.id(), request));
    }
}
