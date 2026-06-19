package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianyu.atlas.ai.AiAdminDtos.SaveModelRequest;
import com.qianyu.atlas.ai.AiAdminDtos.SaveProviderRequest;
import com.qianyu.atlas.ai.AiAdminDtos.SetActiveRequest;
import com.qianyu.atlas.ai.AiAdminDtos.ConfigureEmbeddingRequest;
import com.qianyu.atlas.ai.AiAdminDtos.TestEmbeddingRequest;
import com.qianyu.atlas.ai.AiAdminDtos.TestEmbeddingResponse;
import com.qianyu.atlas.ai.AiAdminDtos.SyncNewApiModelsRequest;
import com.qianyu.atlas.ai.AiAdminDtos.SyncNewApiModelsResponse;
import com.qianyu.atlas.chat.ChatClientFactory;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.rag.EmbeddingClient;
import com.qianyu.atlas.rag.EmbeddingClientFactory;
import com.qianyu.atlas.rag.OpenAiCompatibleEmbeddingClient;
import com.qianyu.atlas.rag.ReindexService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Service
public class AiAdminService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final ProviderRegistry providerRegistry;
    private final EmbeddingClientFactory embeddingClientFactory;
    private final ChatClientFactory chatClientFactory;
    private final ReindexService reindexService;

    public AiAdminService(AiProviderMapper providerMapper,
                          AiModelMapper modelMapper,
                          ProviderRegistry providerRegistry,
                          EmbeddingClientFactory embeddingClientFactory,
                          ChatClientFactory chatClientFactory,
                          ReindexService reindexService) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.providerRegistry = providerRegistry;
        this.embeddingClientFactory = embeddingClientFactory;
        this.chatClientFactory = chatClientFactory;
        this.reindexService = reindexService;
    }

    public List<AiProvider> listProviders() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiProvider>()
                .orderByAsc(AiProvider::getId));
    }

    public AiProvider saveProvider(Long id, SaveProviderRequest request) {
        AiProvider entity;
        if (id == null) {
            entity = new AiProvider();
        } else {
            entity = providerMapper.selectById(id);
            if (entity == null) throw new BizException(404, "渠道不存在");
        }
        entity.setName(request.name());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKey(request.apiKey());
        entity.setRemark(request.remark());
        entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        if (entity.getId() == null) {
            providerMapper.insert(entity);
        } else {
            providerMapper.updateById(entity);
        }
        embeddingClientFactory.invalidate();
        chatClientFactory.invalidate();
        return entity;
    }

    public void deleteProvider(Long id) {
        boolean usedByModels = modelMapper.exists(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, id));
        if (usedByModels) throw new BizException("该渠道下还有模型，无法删除");
        providerMapper.deleteById(id);
        embeddingClientFactory.invalidate();
        chatClientFactory.invalidate();
    }

    public List<AiModel> listModels(String kind) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .orderByAsc(AiModel::getProviderId)
                .orderByAsc(AiModel::getId);
        if (kind != null && !kind.isBlank()) {
            wrapper.eq(AiModel::getKind, kind);
        }
        return modelMapper.selectList(wrapper);
    }

    public AiModel saveModel(Long id, SaveModelRequest request) {
        if (!AiModel.KIND_CHAT.equals(request.kind()) && !AiModel.KIND_EMBEDDING.equals(request.kind())) {
            throw new BizException("kind 只能是 chat 或 embedding");
        }
        AiProvider provider = providerMapper.selectById(request.providerId());
        if (provider == null) throw new BizException(404, "渠道不存在");

        AiModel entity;
        if (id == null) {
            entity = new AiModel();
        } else {
            entity = modelMapper.selectById(id);
            if (entity == null) throw new BizException(404, "模型不存在");
        }
        entity.setProviderId(request.providerId());
        entity.setKind(request.kind());
        entity.setName(request.name());
        entity.setAlias(request.alias());
        entity.setDim(request.dim());
        entity.setRemark(request.remark());
        entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        if (entity.getId() == null) {
            modelMapper.insert(entity);
        } else {
            modelMapper.updateById(entity);
        }
        embeddingClientFactory.invalidate();
        chatClientFactory.invalidate();
        return entity;
    }

    public void deleteModel(Long id) {
        modelMapper.deleteById(id);
        embeddingClientFactory.invalidate();
        chatClientFactory.invalidate();
    }

    public ActiveModelInfo getActive(String kind) {
        return providerRegistry.getActive(kind).orElse(null);
    }

    public ActiveModelInfo setActive(SetActiveRequest request) {
        AiModel model = modelMapper.selectById(request.modelId());
        if (model == null) throw new BizException(404, "模型不存在");
        if (!request.kind().equals(model.getKind())) {
            throw new BizException("模型类型不匹配：模型为 " + model.getKind() + "，请求为 " + request.kind());
        }
        if (AiModel.KIND_EMBEDDING.equals(request.kind())) {
            Integer dim = request.dim();
            if (dim == null || dim <= 0) {
                dim = detectEmbeddingDim(model);
            }
            model.setDim(dim);
            modelMapper.updateById(model);
        }
        providerRegistry.setActive(request.kind(), request.modelId());
        embeddingClientFactory.invalidate();
        chatClientFactory.invalidate();

        if (AiModel.KIND_EMBEDDING.equals(request.kind())) {
            reindexService.reindexAll();
        }
        return providerRegistry.getActive(request.kind()).orElse(null);
    }

    private Integer detectEmbeddingDim(AiModel model) {
        AiProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null) throw new BizException(404, "渠道不存在");
        try {
            EmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                    provider.getBaseUrl(),
                    provider.getApiKey(),
                    model.getName(),
                    0,
                    provider.getId()
            );
            return client.embed("Atlas embedding dimension test").length;
        } catch (Exception exception) {
            throw new BizException(500, "无法自动检测向量维度，请检查该模型是否支持 /embeddings：" + exception.getMessage());
        }
    }

    public ActiveModelInfo configureNewApiEmbedding(ConfigureEmbeddingRequest request) {
        if (request.dim() == null || request.dim() <= 0) {
            throw new BizException("Embedding 维度必须大于 0");
        }

        AiProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getName, "NewAPI")
                .last("limit 1"));
        if (provider == null) {
            provider = new AiProvider();
            provider.setName("NewAPI");
        }
        provider.setBaseUrl(request.baseUrl().trim());
        provider.setApiKey(request.apiKey());
        provider.setRemark("NewAPI OpenAI-compatible embedding provider");
        provider.setEnabled(1);
        if (provider.getId() == null) {
            providerMapper.insert(provider);
        } else {
            providerMapper.updateById(provider);
        }

        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, provider.getId())
                .eq(AiModel::getKind, AiModel.KIND_EMBEDDING)
                .eq(AiModel::getName, request.modelName().trim())
                .last("limit 1"));
        if (model == null) {
            model = new AiModel();
            model.setProviderId(provider.getId());
            model.setKind(AiModel.KIND_EMBEDDING);
            model.setName(request.modelName().trim());
        }
        model.setAlias("NewAPI Embedding");
        model.setDim(request.dim());
        model.setEnabled(1);
        model.setRemark("Configured by quick NewAPI embedding endpoint");
        if (model.getId() == null) {
            modelMapper.insert(model);
        } else {
            modelMapper.updateById(model);
        }

        providerRegistry.setActive(AiModel.KIND_EMBEDDING, model.getId());
        embeddingClientFactory.invalidate();
        reindexService.reindexAll();
        return providerRegistry.getActive(AiModel.KIND_EMBEDDING).orElse(null);
    }

    public TestEmbeddingResponse testEmbedding(TestEmbeddingRequest request) {
        EmbeddingClient client = embeddingClientFactory.current();
        String text = request != null && request.text() != null && !request.text().isBlank()
                ? request.text()
                : "Atlas embedding test";
        float[] vector = client.embed(text);
        return new TestEmbeddingResponse(
                client.modelName(),
                client.dim(),
                vector.length,
                client.providerId()
        );
    }

    public void useLocalEmbeddingFallback() {
        providerRegistry.clearActive(AiModel.KIND_EMBEDDING);
        embeddingClientFactory.invalidate();
        reindexService.reindexAll();
    }

    public SyncNewApiModelsResponse syncNewApiModels(SyncNewApiModelsRequest request) {
        AiProvider provider = upsertNewApiProvider(request.baseUrl(), request.apiKey());
        JsonNode data = fetchModelData(provider);

        int imported = 0;
        int chatModels = 0;
        int embeddingModels = 0;
        for (JsonNode item : data) {
            String modelName = item.path("id").asText("");
            if (modelName.isBlank()) continue;

            String kind = guessKind(modelName);
            Integer dim = AiModel.KIND_EMBEDDING.equals(kind) ? guessEmbeddingDim(modelName) : null;
            upsertModel(provider.getId(), kind, modelName, dim, item.path("owned_by").asText(null));
            imported++;
            if (AiModel.KIND_EMBEDDING.equals(kind)) embeddingModels++;
            else chatModels++;
        }
        embeddingClientFactory.invalidate();
        return new SyncNewApiModelsResponse(provider.getId(), imported, chatModels, embeddingModels);
    }

    private AiProvider upsertNewApiProvider(String baseUrl, String apiKey) {
        AiProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getName, "NewAPI")
                .last("limit 1"));
        if (provider == null) {
            provider = new AiProvider();
            provider.setName("NewAPI");
        }
        provider.setBaseUrl(normalizeBaseUrl(baseUrl));
        provider.setApiKey(apiKey);
        provider.setRemark("NewAPI model gateway");
        provider.setEnabled(1);
        if (provider.getId() == null) providerMapper.insert(provider);
        else providerMapper.updateById(provider);
        return provider;
    }

    private JsonNode fetchModelData(AiProvider provider) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(provider.getBaseUrl()) + "/models"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + (provider.getApiKey() == null ? "" : provider.getApiKey()))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(500, "拉取 NewAPI 模型失败：HTTP " + response.statusCode() + " " + response.body());
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            if (!data.isArray()) {
                throw new BizException(500, "拉取 NewAPI 模型失败：响应中没有 data 数组");
            }
            return data;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(500, "拉取 NewAPI 模型失败：" + exception.getMessage());
        }
    }

    private AiModel upsertModel(Long providerId, String kind, String modelName, Integer dim, String ownedBy) {
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getKind, kind)
                .eq(AiModel::getName, modelName)
                .last("limit 1"));
        if (model == null) {
            model = new AiModel();
            model.setProviderId(providerId);
            model.setKind(kind);
            model.setName(modelName);
        }
        model.setAlias(modelName);
        model.setDim(dim);
        model.setEnabled(1);
        model.setRemark(ownedBy == null || ownedBy.isBlank() ? "Imported from NewAPI" : "Imported from NewAPI · " + ownedBy);
        if (model.getId() == null) modelMapper.insert(model);
        else modelMapper.updateById(model);
        return model;
    }

    private String guessKind(String modelName) {
        String name = modelName.toLowerCase(Locale.ROOT);
        if (name.contains("embedding") || name.contains("embed") || name.contains("bge")) {
            return AiModel.KIND_EMBEDDING;
        }
        return AiModel.KIND_CHAT;
    }

    private Integer guessEmbeddingDim(String modelName) {
        String name = modelName.toLowerCase(Locale.ROOT);
        if (name.contains("gemini-embedding")) return 3072;
        if (name.contains("text-embedding-3-small")) return 1536;
        if (name.contains("text-embedding-3-large")) return 3072;
        if (name.contains("bge") && name.contains("large")) return 1024;
        return 3072;
    }

    private String normalizeBaseUrl(String raw) {
        String base = raw == null ? "" : raw.trim();
        if (base.isEmpty()) {
            throw new BizException("NewAPI baseUrl 不能为空");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/embeddings")) {
            base = base.substring(0, base.length() - "/embeddings".length());
        }
        if (!base.endsWith("/v1")) {
            base = base + "/v1";
        }
        return base;
    }
}
