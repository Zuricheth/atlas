package com.qianyu.atlas.note;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.note.NoteDtos.AgentNoteRequest;
import com.qianyu.atlas.note.NoteDtos.AgentNoteResponse;
import com.qianyu.atlas.note.NoteDtos.NoteHistoryItem;
import com.qianyu.atlas.note.NoteDtos.SaveNoteRequest;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/notes")
@Validated
public class NoteController {
    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @PostMapping
    public ApiResponse<Note> create(@AuthenticationPrincipal CurrentUser currentUser,
                                    @Valid @RequestBody SaveNoteRequest request) {
        return ApiResponse.ok(noteService.create(currentUser.id(), request));
    }

    @PutMapping("/{noteId}")
    public ApiResponse<Note> update(@AuthenticationPrincipal CurrentUser currentUser,
                                    @PathVariable @Positive Long noteId,
                                    @Valid @RequestBody SaveNoteRequest request) {
        return ApiResponse.ok(noteService.update(currentUser.id(), noteId, request));
    }

    @GetMapping("/{noteId}")
    public ApiResponse<NoteDtos.NoteDetail> get(@AuthenticationPrincipal CurrentUser currentUser,
                                                @PathVariable @Positive Long noteId) {
        return ApiResponse.ok(noteService.getDetail(currentUser.id(), noteId));
    }

    @GetMapping
    public ApiResponse<List<Note>> listByNotebook(@AuthenticationPrincipal CurrentUser currentUser,
                                                  @RequestParam @Positive Long notebookId,
                                                  @RequestParam(defaultValue = "false") boolean recursive) {
        return ApiResponse.ok(noteService.listByNotebook(currentUser.id(), notebookId, recursive));
    }

    @GetMapping("/search")
    public ApiResponse<List<Note>> search(@AuthenticationPrincipal CurrentUser currentUser,
                                           @RequestParam @NotBlank @Size(max = 128) String keyword,
                                           @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return ApiResponse.ok(noteService.search(currentUser.id(), keyword, limit));
    }

    @PostMapping("/{noteId}/agent-note")
    public ApiResponse<AgentNoteResponse> generateAgentNote(@AuthenticationPrincipal CurrentUser currentUser,
                                                            @PathVariable @Positive Long noteId,
                                                            @Valid @RequestBody AgentNoteRequest request) {
        return ApiResponse.ok(noteService.generateAgentNote(currentUser.id(), noteId, request.agentId(), request.includeCurrentContent()));
    }

    @PostMapping(value = "/{noteId}/agent-note/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgentNote(@AuthenticationPrincipal CurrentUser currentUser,
                                      @PathVariable @Positive Long noteId,
                                      @Valid @RequestBody AgentNoteRequest request) {
        return noteService.streamAgentNote(currentUser.id(), noteId, request.agentId(), request.includeCurrentContent());
    }

    @DeleteMapping("/{noteId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CurrentUser currentUser,
                                    @PathVariable @Positive Long noteId,
                                    @RequestParam(defaultValue = "note") @Size(max = 16) String mode) {
        noteService.deleteWithMode(currentUser.id(), noteId, mode);
        return ApiResponse.ok();
    }

    @GetMapping("/{noteId}/history")
    public ApiResponse<List<NoteHistoryItem>> listHistory(@AuthenticationPrincipal CurrentUser currentUser,
                                                          @PathVariable @Positive Long noteId) {
        return ApiResponse.ok(noteService.listHistory(currentUser.id(), noteId));
    }

    @PostMapping("/{noteId}/history/{historyId}/rollback")
    public ApiResponse<NoteDtos.NoteDetail> rollback(@AuthenticationPrincipal CurrentUser currentUser,
                                                     @PathVariable @Positive Long noteId,
                                                     @PathVariable @Positive Long historyId) {
        return ApiResponse.ok(noteService.rollback(currentUser.id(), noteId, historyId));
    }
}
