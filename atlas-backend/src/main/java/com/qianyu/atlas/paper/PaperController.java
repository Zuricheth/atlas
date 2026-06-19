package com.qianyu.atlas.paper;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.paper.PaperDtos.ImportPaperResponse;
import com.qianyu.atlas.paper.PaperService.DownloadedPaper;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/papers")
@Validated
public class PaperController {
    private final PaperService paperService;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
    }

    @PostMapping("/import")
    public ApiResponse<ImportPaperResponse> importPaper(@AuthenticationPrincipal CurrentUser currentUser,
                                                        @RequestParam @Positive Long notebookId,
                                                        @RequestParam @NotBlank @Size(max = 128) String title,
                                                        @RequestParam(required = false) @Size(max = 512) String summary,
                                                        @RequestParam @NotBlank String markdownContent,
                                                        @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(paperService.importPaper(
                currentUser.id(),
                notebookId,
                title,
                summary,
                markdownContent,
                file
        ));
    }

    @PostMapping("/import/ai")
    public ApiResponse<ImportPaperResponse> importPaperWithAi(@AuthenticationPrincipal CurrentUser currentUser,
                                                              @RequestParam @Positive Long notebookId,
                                                              @RequestParam(required = false) @Size(max = 128) String titleHint,
                                                              @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(paperService.importPaperWithAi(
                currentUser.id(),
                notebookId,
                titleHint,
                file
        ));
    }

    @GetMapping("/{attachmentId}/file")
    public ResponseEntity<Resource> getPaperFile(@AuthenticationPrincipal CurrentUser currentUser,
                                                 @PathVariable @Positive Long attachmentId) {
        DownloadedPaper paper = paperService.getPaperFile(currentUser.id(), attachmentId);
        String contentType = paper.contentType() == null ? MediaType.APPLICATION_PDF_VALUE : paper.contentType();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(paper.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(paper.resource());
    }
}
