package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ProviderRegistry {
    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiActiveModelMapper activeMapper;

    public ProviderRegistry(AiProviderMapper providerMapper,
                            AiModelMapper modelMapper,
                            AiActiveModelMapper activeMapper) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.activeMapper = activeMapper;
    }

    public Optional<ActiveModelInfo> getActive(String kind) {
        AiActiveModel active = activeMapper.selectOne(new LambdaQueryWrapper<AiActiveModel>()
                .eq(AiActiveModel::getScope, AiActiveModel.SCOPE_SYSTEM)
                .eq(AiActiveModel::getKind, kind)
                .last("limit 1"));
        if (active == null) return Optional.empty();

        AiModel model = modelMapper.selectById(active.getModelId());
        if (model == null || model.getEnabled() == null || model.getEnabled() == 0) {
            return Optional.empty();
        }

        AiProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() == 0) {
            return Optional.empty();
        }
        return Optional.of(new ActiveModelInfo(provider, model));
    }

    public Optional<ActiveModelInfo> getByModelId(Long modelId) {
        if (modelId == null) return Optional.empty();
        AiModel model = modelMapper.selectById(modelId);
        if (model == null || model.getEnabled() == null || model.getEnabled() == 0) {
            return Optional.empty();
        }
        AiProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || provider.getEnabled() == null || provider.getEnabled() == 0) {
            return Optional.empty();
        }
        return Optional.of(new ActiveModelInfo(provider, model));
    }

    public void setActive(String kind, Long modelId) {
        AiActiveModel existing = activeMapper.selectOne(new LambdaQueryWrapper<AiActiveModel>()
                .eq(AiActiveModel::getScope, AiActiveModel.SCOPE_SYSTEM)
                .eq(AiActiveModel::getKind, kind)
                .last("limit 1"));
        if (existing == null) {
            AiActiveModel created = new AiActiveModel();
            created.setScope(AiActiveModel.SCOPE_SYSTEM);
            created.setKind(kind);
            created.setModelId(modelId);
            activeMapper.insert(created);
        } else {
            existing.setModelId(modelId);
            activeMapper.updateById(existing);
        }
    }

    public void clearActive(String kind) {
        activeMapper.delete(new LambdaQueryWrapper<AiActiveModel>()
                .eq(AiActiveModel::getScope, AiActiveModel.SCOPE_SYSTEM)
                .eq(AiActiveModel::getKind, kind));
    }
}
