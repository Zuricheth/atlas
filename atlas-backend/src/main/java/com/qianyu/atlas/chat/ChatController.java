package com.qianyu.atlas.chat;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/rag")
    public ApiResponse<ChatDtos.RagResponse> rag(@AuthenticationPrincipal CurrentUser currentUser,
                                                 @Valid @RequestBody ChatDtos.RagRequest request) {
        return ApiResponse.ok(chatService.rag(currentUser.id(), request));
    }

    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ragStream(@AuthenticationPrincipal CurrentUser currentUser,
                                @RequestParam String question,
                                @RequestParam(required = false) Integer topK,
                                @RequestParam(required = false) Long notebookId) {
        return chatService.ragStream(currentUser.id(), new ChatDtos.RagRequest(question, topK, notebookId));
    }
}