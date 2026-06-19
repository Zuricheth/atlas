package com.qianyu.atlas.inbox;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.inbox.InboxDtos.AcceptInboxRequest;
import com.qianyu.atlas.inbox.InboxDtos.AcceptInboxResponse;
import com.qianyu.atlas.inbox.InboxDtos.InboxRequestView;
import com.qianyu.atlas.inbox.InboxDtos.PlanInboxRequest;
import com.qianyu.atlas.inbox.InboxDtos.PlanInboxResponse;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/inbox")
@Validated
public class InboxController {
    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @PostMapping("/requests")
    public ApiResponse<InboxRequestView> create(@AuthenticationPrincipal CurrentUser currentUser,
                                                @RequestParam(required = false) @Size(max = 128) String sourceProject,
                                                @RequestParam(required = false) @Size(max = 128) String title,
                                                @RequestParam(required = false) @Size(max = 512) String description,
                                                @RequestParam(required = false) @Size(max = 300) List<@Size(max = 500) String> paths,
                                                @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(inboxService.create(
                currentUser.id(),
                sourceProject,
                title,
                description,
                paths,
                files
        ));
    }

    @GetMapping("/requests")
    public ApiResponse<List<InboxRequestView>> list(@AuthenticationPrincipal CurrentUser currentUser,
                                                    @RequestParam(required = false) @Size(max = 24) String status) {
        return ApiResponse.ok(inboxService.list(currentUser.id(), status));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<InboxRequestView> get(@AuthenticationPrincipal CurrentUser currentUser,
                                             @PathVariable @Positive Long requestId) {
        return ApiResponse.ok(inboxService.get(currentUser.id(), requestId));
    }

    @PostMapping("/requests/{requestId}/accept")
    public ApiResponse<AcceptInboxResponse> accept(@AuthenticationPrincipal CurrentUser currentUser,
                                                   @PathVariable @Positive Long requestId,
                                                   @Valid @RequestBody AcceptInboxRequest request) {
        return ApiResponse.ok(inboxService.accept(currentUser.id(), requestId, request));
    }

    @PostMapping("/requests/{requestId}/plan")
    public ApiResponse<PlanInboxResponse> plan(@AuthenticationPrincipal CurrentUser currentUser,
                                               @PathVariable @Positive Long requestId,
                                               @Valid @RequestBody(required = false) PlanInboxRequest request) {
        return ApiResponse.ok(inboxService.plan(currentUser.id(), requestId, request));
    }

    @PostMapping("/requests/{requestId}/reject")
    public ApiResponse<Void> reject(@AuthenticationPrincipal CurrentUser currentUser,
                                    @PathVariable @Positive Long requestId) {
        inboxService.reject(currentUser.id(), requestId);
        return ApiResponse.ok();
    }
}
