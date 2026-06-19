package com.qianyu.atlas.chat;

import com.qianyu.atlas.ai.ActiveModelInfo;
import com.qianyu.atlas.ai.AiModel;
import com.qianyu.atlas.ai.AiProvider;
import com.qianyu.atlas.ai.ProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ChatClientFactory {
    private final ProviderRegistry providerRegistry;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public ChatClientFactory(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public ChatClient current() {
        Optional<ActiveModelInfo> active = providerRegistry.getActive(AiModel.KIND_CHAT);
        if (active.isEmpty()) {
            return cachedOr(null, null, new LocalRagChatClient());
        }

        return fromActive(active.get());
    }

    public ChatClient forModel(Long modelId) {
        Optional<ActiveModelInfo> active = providerRegistry.getByModelId(modelId);
        if (active.isEmpty()) {
            return current();
        }
        if (!AiModel.KIND_CHAT.equals(active.get().model().getKind())) {
            return current();
        }
        return fromActive(active.get());
    }

    private ChatClient fromActive(ActiveModelInfo active) {
        AiProvider provider = active.provider();
        AiModel model = active.model();
        return cachedOr(provider.getId(), model.getId(),
                new OpenAiCompatibleChatClient(
                        provider.getBaseUrl(),
                        provider.getApiKey(),
                        model.getName(),
                        provider.getId()
                ));
    }

    public void invalidate() {
        cache.set(null);
    }

    private ChatClient cachedOr(Long providerId, Long modelId, ChatClient fresh) {
        Cached existing = cache.get();
        if (existing != null
                && Objects.equals(existing.providerId, providerId)
                && Objects.equals(existing.modelId, modelId)) {
            return existing.client;
        }
        cache.set(new Cached(providerId, modelId, fresh));
        return fresh;
    }

    private record Cached(Long providerId, Long modelId, ChatClient client) {
    }
}
