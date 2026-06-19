package com.qianyu.atlas.ai;

import com.qianyu.atlas.ai.AiAdminDtos.SaveModelRequest;
import com.qianyu.atlas.ai.AiAdminDtos.SaveProviderRequest;
import com.qianyu.atlas.ai.AiAdminDtos.SetActiveRequest;
import com.qianyu.atlas.ai.AiAdminDtos.ConfigureEmbeddingRequest;
import com.qianyu.atlas.ai.AiAdminDtos.TestEmbeddingRequest;
import com.qianyu.atlas.ai.AiAdminDtos.TestEmbeddingResponse;
import com.qianyu.atlas.ai.AiAdminDtos.SyncNewApiModelsRequest;
import com.qianyu.atlas.ai.AiAdminDtos.SyncNewApiModelsResponse;
import com.qianyu.atlas.ai.AiAdminDtos.SaveAgentRequest;
import com.qianyu.atlas.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/ai")
@Validated
public class AiAdminController {
    private final AiAdminService aiAdminService;
    private final AiAgentService aiAgentService;

    public AiAdminController(AiAdminService aiAdminService,
                             AiAgentService aiAgentService) {
        this.aiAdminService = aiAdminService;
        this.aiAgentService = aiAgentService;
    }

    @GetMapping("/providers")
    public ApiResponse<List<AiProvider>> listProviders() {
        return ApiResponse.ok(aiAdminService.listProviders());
    }

    @PostMapping("/providers")
    public ApiResponse<AiProvider> createProvider(@Valid @RequestBody SaveProviderRequest request) {
        return ApiResponse.ok(aiAdminService.saveProvider(null, request));
    }

    @PutMapping("/providers/{id}")
    public ApiResponse<AiProvider> updateProvider(@PathVariable @Positive Long id,
                                                  @Valid @RequestBody SaveProviderRequest request) {
        return ApiResponse.ok(aiAdminService.saveProvider(id, request));
    }

    @DeleteMapping("/providers/{id}")
    public ApiResponse<Void> deleteProvider(@PathVariable @Positive Long id) {
        aiAdminService.deleteProvider(id);
        return ApiResponse.ok();
    }

    @GetMapping("/models")
    public ApiResponse<List<AiModel>> listModels(@RequestParam(required = false) @Size(max = 32) String kind) {
        return ApiResponse.ok(aiAdminService.listModels(kind));
    }

    @PostMapping("/models")
    public ApiResponse<AiModel> createModel(@Valid @RequestBody SaveModelRequest request) {
        return ApiResponse.ok(aiAdminService.saveModel(null, request));
    }

    @PutMapping("/models/{id}")
    public ApiResponse<AiModel> updateModel(@PathVariable @Positive Long id,
                                            @Valid @RequestBody SaveModelRequest request) {
        return ApiResponse.ok(aiAdminService.saveModel(id, request));
    }

    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable @Positive Long id) {
        aiAdminService.deleteModel(id);
        return ApiResponse.ok();
    }

    @GetMapping("/active")
    public ApiResponse<ActiveModelInfo> getActive(@RequestParam @NotBlank @Size(max = 32) String kind) {
        return ApiResponse.ok(aiAdminService.getActive(kind));
    }

    @PutMapping("/active")
    public ApiResponse<ActiveModelInfo> setActive(@Valid @RequestBody SetActiveRequest request) {
        return ApiResponse.ok(aiAdminService.setActive(request));
    }

    @PostMapping("/embedding/newapi")
    public ApiResponse<ActiveModelInfo> configureNewApiEmbedding(@Valid @RequestBody ConfigureEmbeddingRequest request) {
        return ApiResponse.ok(aiAdminService.configureNewApiEmbedding(request));
    }

    @PostMapping("/embedding/test")
    public ApiResponse<TestEmbeddingResponse> testEmbedding(@Valid @RequestBody TestEmbeddingRequest request) {
        return ApiResponse.ok(aiAdminService.testEmbedding(request));
    }

    @PostMapping("/embedding/local-fallback")
    public ApiResponse<Void> useLocalEmbeddingFallback() {
        aiAdminService.useLocalEmbeddingFallback();
        return ApiResponse.ok();
    }

    @PostMapping("/newapi/sync-models")
    public ApiResponse<SyncNewApiModelsResponse> syncNewApiModels(@Valid @RequestBody SyncNewApiModelsRequest request) {
        return ApiResponse.ok(aiAdminService.syncNewApiModels(request));
    }

    @GetMapping("/agents")
    public ApiResponse<List<AiAgent>> listAgents() {
        return ApiResponse.ok(aiAgentService.list());
    }

    @PostMapping("/agents/atlas-defaults")
    public ApiResponse<List<AiAgent>> ensureAtlasSystemAgents() {
        return ApiResponse.ok(aiAgentService.ensureAtlasSystemAgents());
    }

    @PostMapping("/agents")
    public ApiResponse<AiAgent> saveAgent(@Valid @RequestBody SaveAgentRequest request) {
        return ApiResponse.ok(aiAgentService.save(request));
    }

    @DeleteMapping("/agents/{id}")
    public ApiResponse<Void> deleteAgent(@PathVariable @Positive Long id) {
        aiAgentService.delete(id);
        return ApiResponse.ok();
    }
}
