package com.qianyu.atlas.deepwiki;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.deepwiki.DeepWikiDtos.GenerateWikiRequest;
import com.qianyu.atlas.deepwiki.DeepWikiDtos.GenerateWikiResponse;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deepwiki")
@Validated
public class DeepWikiController {
    private final DeepWikiService deepWikiService;

    public DeepWikiController(DeepWikiService deepWikiService) {
        this.deepWikiService = deepWikiService;
    }

    @PostMapping("/generate")
    public ApiResponse<GenerateWikiResponse> generate(@AuthenticationPrincipal CurrentUser currentUser,
                                                      @Valid @RequestBody GenerateWikiRequest request) {
        return ApiResponse.ok(deepWikiService.generate(currentUser.id(), request));
    }

    @GetMapping("/latest")
    public ApiResponse<GenerateWikiResponse> latest(@AuthenticationPrincipal CurrentUser currentUser,
                                                    @RequestParam @Positive Long notebookId,
                                                    @RequestParam(defaultValue = "home") @Size(max = 32) String mode,
                                                    @RequestParam(defaultValue = "") @Size(max = 128) String focus) {
        return ApiResponse.ok(deepWikiService.latest(currentUser.id(), notebookId, mode, focus));
    }
}
