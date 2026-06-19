package com.qianyu.atlas.rag;

import com.qianyu.atlas.ai.ActiveModelInfo;
import com.qianyu.atlas.ai.AiModel;
import com.qianyu.atlas.ai.AiProvider;
import com.qianyu.atlas.ai.ProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EmbeddingClientFactory {
    private final ProviderRegistry providerRegistry;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public EmbeddingClientFactory(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public EmbeddingClient current() {
        Optional<ActiveModelInfo> active = providerRegistry.getActive(AiModel.KIND_EMBEDDING);

        if (active.isEmpty()) {
            return cachedOr(null, null, new HashEmbeddingClient());
        }

        AiProvider provider = active.get().provider();
        AiModel model = active.get().model();
        int dim = model.getDim() == null ? 0 : model.getDim();

        return cachedOr(provider.getId(), model.getId(),
                new OpenAiCompatibleEmbeddingClient(
                        provider.getBaseUrl(),
                        provider.getApiKey(),
                        model.getName(),
                        dim,
                        provider.getId()
                ));
    }

    public void invalidate() {
        cache.set(null);
    }

    private EmbeddingClient cachedOr(Long providerId, Long modelId, EmbeddingClient fresh) {
        Cached existing = cache.get();
        if (existing != null
                && Objects.equals(existing.providerId, providerId)
                && Objects.equals(existing.modelId, modelId)) {
            return existing.client;
        }
        cache.set(new Cached(providerId, modelId, fresh));
        return fresh;
    }

    private record Cached(Long providerId, Long modelId, EmbeddingClient client) {
    }
}