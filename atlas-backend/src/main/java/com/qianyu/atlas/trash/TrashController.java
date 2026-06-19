package com.qianyu.atlas.trash;

import com.qianyu.atlas.common.ApiResponse;
import com.qianyu.atlas.security.CurrentUser;
import com.qianyu.atlas.trash.TrashDtos.TrashItem;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trash")
@Validated
public class TrashController {
    private final TrashService trashService;

    public TrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    @GetMapping
    public ApiResponse<List<TrashItem>> list(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(trashService.list(currentUser.id()));
    }

    @PostMapping("/{kind}/{id}/restore")
    public ApiResponse<Void> restore(@AuthenticationPrincipal CurrentUser currentUser,
                                     @PathVariable @Pattern(regexp = "note|library|paper") String kind,
                                     @PathVariable @Positive Long id) {
        trashService.restore(currentUser.id(), kind, id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{kind}/{id}")
    public ApiResponse<Void> purge(@AuthenticationPrincipal CurrentUser currentUser,
                                   @PathVariable @Pattern(regexp = "note|library|paper") String kind,
                                   @PathVariable @Positive Long id) {
        trashService.purge(currentUser.id(), kind, id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/expired")
    public ApiResponse<Integer> purgeExpired(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(trashService.purgeExpired(currentUser.id()));
    }

    @DeleteMapping
    public ApiResponse<Integer> purgeAll(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(trashService.purgeAll(currentUser.id()));
    }
}
