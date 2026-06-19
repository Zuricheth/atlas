package com.qianyu.atlas.asset;

import com.qianyu.atlas.asset.AssetDtos.AssetItem;
import com.qianyu.atlas.asset.AssetDtos.BulkAssetRequest;
import com.qianyu.atlas.asset.AssetDtos.BulkAssetResponse;
import com.qianyu.atlas.asset.AssetDtos.SpaceSummary;
import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/assets")
@Validated
public class AssetController {
    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/summary")
    public ApiResponse<SpaceSummary> summary(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(assetService.summary(currentUser.id()));
    }

    @GetMapping("/items")
    public ApiResponse<List<AssetItem>> items(@AuthenticationPrincipal CurrentUser currentUser,
                                              @RequestParam(required = false) @Positive Long notebookId,
                                              @RequestParam(required = false) @Size(max = 32) String type,
                                              @RequestParam(required = false) @Size(max = 128) String q,
                                              @RequestParam(defaultValue = "240") @Min(1) @Max(500) int limit) {
        return ApiResponse.ok(assetService.items(currentUser.id(), notebookId, type, q, limit));
    }

    @PostMapping("/trash")
    public ApiResponse<BulkAssetResponse> trash(@AuthenticationPrincipal CurrentUser currentUser,
                                                @Valid @RequestBody BulkAssetRequest request) {
        return ApiResponse.ok(assetService.trash(currentUser.id(), request == null ? null : request.keys()));
    }

    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal CurrentUser currentUser,
                                                        @Valid @RequestBody BulkAssetRequest request) {
        List<String> keys = request == null ? null : request.keys();
        StreamingResponseBody body = outputStream -> assetService.writeArchive(currentUser.id(), keys, outputStream);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Atlas选中资产_" + timestamp + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }
}
