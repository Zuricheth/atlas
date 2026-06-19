package com.qianyu.atlas.rag;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@Validated
public class SearchController {
    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/semantic")
    public ApiResponse<List<SearchHit>> semantic(@AuthenticationPrincipal CurrentUser currentUser,
                                                 @RequestParam @NotBlank @Size(max = 256) String query,
                                                 @RequestParam(required = false) @Min(1) @Max(50) Integer topK) {
        return ApiResponse.ok(searchService.semantic(currentUser.id(), query, topK));
    }

    @GetMapping("/keyword")
    public ApiResponse<List<SearchHit>> keyword(@AuthenticationPrincipal CurrentUser currentUser,
                                                @RequestParam @NotBlank @Size(max = 256) String query,
                                                @RequestParam(required = false) @Min(1) @Max(50) Integer topK) {
        return ApiResponse.ok(searchService.keyword(currentUser.id(), query, topK));
    }

    @GetMapping("/matches")
    public ApiResponse<List<SearchMatchDetail>> matches(@AuthenticationPrincipal CurrentUser currentUser,
                                                        @RequestParam @NotBlank @Size(max = 256) String query,
                                                        @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return ApiResponse.ok(searchService.matchDetails(currentUser.id(), query, limit));
    }

    @GetMapping("/hybrid")
    public ApiResponse<List<SearchHit>> hybrid(@AuthenticationPrincipal CurrentUser currentUser,
                                               @RequestParam @NotBlank @Size(max = 256) String query,
                                               @RequestParam(required = false) @Min(1) @Max(50) Integer topK) {
        return ApiResponse.ok(searchService.hybrid(currentUser.id(), query, topK));
    }

    @GetMapping("/tree")
    public ApiResponse<SearchTreeDtos.SearchTreeResponse> tree(@AuthenticationPrincipal CurrentUser currentUser,
                                                               @RequestParam @NotBlank @Size(max = 256) String query,
                                                               @RequestParam(required = false) @Min(10) @Max(300) Integer limit) {
        return ApiResponse.ok(searchService.tree(currentUser.id(), query, limit));
    }
}
