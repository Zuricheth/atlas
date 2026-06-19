package com.qianyu.atlas.library;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.library.LibraryDtos.FolderImportResponse;
import com.qianyu.atlas.library.LibraryDtos.FolderPlanRequest;
import com.qianyu.atlas.library.LibraryDtos.FolderPlanResponse;
import com.qianyu.atlas.library.LibraryDtos.ImportLibraryItemResponse;
import com.qianyu.atlas.library.LibraryDtos.LibraryItemView;
import com.qianyu.atlas.library.LibraryService.DownloadedLibraryFile;
import com.qianyu.atlas.security.CurrentUser;
import jakarta.validation.Valid;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/library")
@Validated
public class LibraryController {
    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @PostMapping("/import")
    public ApiResponse<ImportLibraryItemResponse> importItem(@AuthenticationPrincipal CurrentUser currentUser,
                                                             @RequestParam @Positive Long notebookId,
                                                             @RequestParam(required = false) @Size(max = 128) String title,
                                                             @RequestParam(required = false) @Size(max = 255) String category,
                                                             @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(libraryService.importItem(
                currentUser.id(),
                notebookId,
                title,
                category,
                file
        ));
    }

    @PostMapping("/auto-import")
    public ApiResponse<ImportLibraryItemResponse> autoImport(@AuthenticationPrincipal CurrentUser currentUser,
                                                             @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(libraryService.autoImport(currentUser.id(), file));
    }

    @PostMapping("/folder-import")
    public ApiResponse<FolderImportResponse> folderImport(@AuthenticationPrincipal CurrentUser currentUser,
                                                          @RequestPart("files") List<MultipartFile> files,
                                                          @RequestParam(required = false) List<String> paths,
                                                          @RequestParam(required = false) String planJson) {
        return ApiResponse.ok(libraryService.importFolder(currentUser.id(), files, paths, planJson));
    }

    @PostMapping("/folder-plan")
    public ApiResponse<FolderPlanResponse> folderPlan(@AuthenticationPrincipal CurrentUser currentUser,
                                                      @Valid @RequestBody FolderPlanRequest request) {
        return ApiResponse.ok(libraryService.planFolder(currentUser.id(), request));
    }

    @GetMapping
    public ApiResponse<List<LibraryItemView>> list(@AuthenticationPrincipal CurrentUser currentUser,
                                                   @RequestParam(required = false) @Positive Long notebookId,
                                                   @RequestParam(defaultValue = "false") boolean recursive) {
        return ApiResponse.ok(libraryService.list(currentUser.id(), notebookId, recursive));
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> exportArchive(@AuthenticationPrincipal CurrentUser currentUser,
                                                               @RequestParam(required = false) @Positive Long notebookId,
                                                               @RequestParam(defaultValue = "true") boolean recursive) {
        String filename = libraryService.exportArchiveFilename(currentUser.id(), notebookId);
        StreamingResponseBody body = outputStream -> libraryService.writeArchive(currentUser.id(), notebookId, recursive, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    @GetMapping("/{itemId}/file")
    public ResponseEntity<Resource> getFile(@AuthenticationPrincipal CurrentUser currentUser,
                                            @PathVariable @Positive Long itemId) {
        DownloadedLibraryFile file = libraryService.getFile(currentUser.id(), itemId);
        String contentType = file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.contentType();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(file.resource());
    }

    @DeleteMapping("/{itemId}")
    public ApiResponse<Void> deleteItem(@AuthenticationPrincipal CurrentUser currentUser,
                                        @PathVariable @Positive Long itemId,
                                        @RequestParam(defaultValue = "item") @Size(max = 16) String mode) {
        libraryService.deleteItem(currentUser.id(), itemId, mode);
        return ApiResponse.ok();
    }
}
